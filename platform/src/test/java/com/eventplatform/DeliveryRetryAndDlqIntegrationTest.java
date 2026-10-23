package com.eventplatform;

import com.eventplatform.delivery.BackoffCalculator;
import com.eventplatform.delivery.ClaimRepository;
import com.eventplatform.delivery.DeliveryAttemptRepository;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryExecutor;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.delivery.DeliveryWorker;
import com.eventplatform.event.EventEntity;
import com.eventplatform.event.EventRepository;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.subscription.SubscriptionStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DeliveryRetryAndDlqIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("events_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryExecutor deliveryExecutor;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private BackoffCalculator backoffCalculator;

    private static HttpServer testHttpServer;
    private static int serverPort;
    private static final AtomicInteger responseStatusToReturn = new AtomicInteger(200);

    @BeforeAll
    static void startHttpServer() throws IOException {
        testHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = testHttpServer.getAddress().getPort();
        testHttpServer.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int status = responseStatusToReturn.get();
                byte[] responseBytes = ("{\"status\": " + status + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        });
        testHttpServer.setExecutor(Executors.newCachedThreadPool());
        testHttpServer.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (testHttpServer != null) {
            testHttpServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        deliveryAttemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        subscriptionRepository.deleteAll();
        responseStatusToReturn.set(200);
    }

    private SubscriptionEntity createSub() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Retry Sub");
        sub.setUrl("http://localhost:" + serverPort + "/webhook");
        sub.setSecret("whsec_retry_secret");
        sub.setEventTypes(List.of("*"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        return subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("Retryable 500 error increments attemptCount, schedules nextAttemptAt, and stays PENDING")
    void testRetryableFailureSchedulesRetry() {
        responseStatusToReturn.set(500);
        SubscriptionEntity sub = createSub();
        EventEntity event = eventRepository.save(new EventEntity("idem_500_" + UUID.randomUUID(), "test", null, "{}"));

        DeliveryEntity delivery = deliveryRepository.save(new DeliveryEntity(event.getId(), sub.getId()));

        deliveryExecutor.executeDelivery(delivery, "worker-1");

        DeliveryEntity updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextAttemptAt()).isAfterOrEqualTo(Instant.now().minusSeconds(1));
        assertThat(updated.getLastError()).contains("500");
        assertThat(updated.getLockedBy()).isNull();
        assertThat(updated.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("Non-retryable 404 error moves immediately to DEAD without retrying")
    void testNonRetryableFailureGoesStraightToDead() {
        responseStatusToReturn.set(404);
        SubscriptionEntity sub = createSub();
        EventEntity event = eventRepository.save(new EventEntity("idem_404_" + UUID.randomUUID(), "test", null, "{}"));

        DeliveryEntity delivery = deliveryRepository.save(new DeliveryEntity(event.getId(), sub.getId()));

        deliveryExecutor.executeDelivery(delivery, "worker-1");

        DeliveryEntity updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getDeadAt()).isNotNull();
        assertThat(updated.getLastError()).contains("404");
        assertThat(updated.getLockedBy()).isNull();
    }

    @Test
    @DisplayName("Retries exhaust after maxAttempts and transition delivery to DEAD (DLQ)")
    void testExhaustedRetriesLandInDead() {
        responseStatusToReturn.set(503);
        SubscriptionEntity sub = createSub();
        EventEntity event = eventRepository.save(new EventEntity("idem_exhaust_" + UUID.randomUUID(), "test", null, "{}"));

        DeliveryEntity delivery = deliveryRepository.save(new DeliveryEntity(event.getId(), sub.getId()));
        int maxAttempts = backoffCalculator.getMaxAttempts(); // 3 in test profile

        for (int i = 1; i <= maxAttempts; i++) {
            deliveryExecutor.executeDelivery(delivery, "worker-1");
            delivery = deliveryRepository.findById(delivery.getId()).orElseThrow();

            if (i < maxAttempts) {
                assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
                assertThat(delivery.getAttemptCount()).isEqualTo(i);
            }
        }

        // After max attempts, delivery must be DEAD
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.getAttemptCount()).isEqualTo(maxAttempts);
        assertThat(delivery.getDeadAt()).isNotNull();
        assertThat(delivery.getLastError()).contains("Retries exhausted");
    }

    @Test
    @DisplayName("Worker crash simulation: expired lease is claimed by another worker and completed")
    void testWorkerCrashLeaseExpiryRecovery() {
        SubscriptionEntity sub = createSub();
        EventEntity event = eventRepository.save(new EventEntity("idem_crash_" + UUID.randomUUID(), "test", null, "{}"));

        // Simulate Worker 1 claiming delivery and then crashing (locked_until has passed)
        DeliveryEntity delivery = new DeliveryEntity(event.getId(), sub.getId());
        delivery.setStatus(DeliveryStatus.IN_FLIGHT);
        delivery.setLockedBy("worker-crashed-1");
        delivery.setLockedUntil(Instant.now().minusSeconds(10)); // Lease expired in past
        delivery = deliveryRepository.save(delivery);

        // Worker 2 runs claim query
        List<DeliveryEntity> claimedByWorker2 = claimRepository.claimDueDeliveries("worker-healthy-2", 10, 30);
        assertThat(claimedByWorker2).hasSize(1);
        assertThat(claimedByWorker2.get(0).getId()).isEqualTo(delivery.getId());
        assertThat(claimedByWorker2.get(0).getLockedBy()).isEqualTo("worker-healthy-2");

        // Worker 2 executes delivery successfully
        responseStatusToReturn.set(200);
        deliveryExecutor.executeDelivery(claimedByWorker2.get(0), "worker-healthy-2");

        // Delivery successfully completed by Worker 2
        DeliveryEntity completed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(completed.getLockedBy()).isNull();
    }
}

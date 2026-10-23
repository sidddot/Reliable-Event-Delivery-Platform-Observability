package com.eventplatform;

import com.eventplatform.delivery.ClaimRepository;
import com.eventplatform.delivery.DeliveryAttemptEntity;
import com.eventplatform.delivery.DeliveryAttemptRepository;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryExecutor;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.delivery.DeliveryWorker;
import com.eventplatform.event.EventEntity;
import com.eventplatform.event.EventRepository;
import com.eventplatform.signing.HmacSigner;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DeliveryWorkerIntegrationTest {

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
    private ClaimRepository claimRepository;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private HmacSigner hmacSigner;

    private static HttpServer testHttpServer;
    private static int serverPort;
    private static final String RECEIVER_SECRET = "whsec_test_secret_for_worker_testing";
    private static final AtomicReference<String> lastReceivedSignature = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedPayload = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedEventId = new AtomicReference<>();
    private static final AtomicBoolean signatureVerified = new AtomicBoolean(false);

    @BeforeAll
    static void startHttpServer() throws IOException {
        testHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = testHttpServer.getAddress().getPort();
        testHttpServer.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String webhookId = exchange.getRequestHeaders().getFirst(HmacSigner.WEBHOOK_ID_HEADER);
                String timestampStr = exchange.getRequestHeaders().getFirst(HmacSigner.TIMESTAMP_HEADER);
                String signatureHeader = exchange.getRequestHeaders().getFirst(HmacSigner.SIGNATURE_HEADER);

                lastReceivedPayload.set(body);
                lastReceivedEventId.set(webhookId);
                lastReceivedSignature.set(signatureHeader);

                HmacSigner signer = new HmacSigner();
                String expectedSig = signer.sign(RECEIVER_SECRET, webhookId, Long.parseLong(timestampStr), body);
                boolean valid = expectedSig.equals(signatureHeader);
                signatureVerified.set(valid);

                int statusCode = valid ? 200 : 401;
                byte[] responseBytes = ("{\"verified\": " + valid + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, responseBytes.length);
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
        signatureVerified.set(false);
        lastReceivedSignature.set(null);
        lastReceivedPayload.set(null);
        lastReceivedEventId.set(null);
    }

    @Test
    @DisplayName("Worker claims delivery, signs request, dispatches HTTP, records attempt, ends SUCCEEDED")
    void testEndToEndSuccessfulSignedDelivery() throws Exception {
        // Create active subscription pointing to our mock HTTP server
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Test Endpoint");
        sub.setUrl("http://localhost:" + serverPort + "/webhook");
        sub.setSecret(RECEIVER_SECRET);
        sub.setEventTypes(List.of("*"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setTimeoutMs(5000);
        SubscriptionEntity savedSub = subscriptionRepository.save(sub);

        // Create event
        String payload = "{\"event\": \"user.signup\", \"userId\": \"usr_999\"}";
        EventEntity event = new EventEntity("idem_worker_test_" + UUID.randomUUID(), "user.signup", null, payload);
        EventEntity savedEvent = eventRepository.save(event);

        // Create pending delivery due now
        DeliveryEntity delivery = new DeliveryEntity(savedEvent.getId(), savedSub.getId());
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setNextAttemptAt(Instant.now().minusSeconds(1));
        DeliveryEntity savedDelivery = deliveryRepository.save(delivery);

        // Poll once with worker
        int claimed = deliveryWorker.pollOnce();
        assertThat(claimed).isEqualTo(1);

        // Wait for async execution in worker thread pool
        awaitDeliveryStatus(savedDelivery.getId(), DeliveryStatus.SUCCEEDED, 5, TimeUnit.SECONDS);

        // Verify receiver received valid HMAC signature
        assertThat(signatureVerified.get()).isTrue();
        assertThat(lastReceivedEventId.get()).isEqualTo(savedEvent.getId().toString());
        assertThat(lastReceivedPayload.get()).isEqualTo(payload);

        // Verify delivery record in DB
        DeliveryEntity updatedDelivery = deliveryRepository.findById(savedDelivery.getId()).orElseThrow();
        assertThat(updatedDelivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(updatedDelivery.getAttemptCount()).isEqualTo(1);
        assertThat(updatedDelivery.getLockedBy()).isNull();
        assertThat(updatedDelivery.getLockedUntil()).isNull();
        assertThat(updatedDelivery.getLastError()).isNull();

        // Verify attempt record in DB
        List<DeliveryAttemptEntity> attempts = deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNoAsc(savedDelivery.getId());
        assertThat(attempts).hasSize(1);
        DeliveryAttemptEntity attempt = attempts.get(0);
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getHttpStatus()).isEqualTo(200);
        assertThat(attempt.getWorkerId()).isEqualTo(deliveryWorker.getWorkerId());
        assertThat(attempt.getResponseSnippet()).contains("verified");
    }

    @Test
    @DisplayName("Two concurrent workers claiming work simultaneously NEVER claim the same delivery")
    void testTwoWorkersNeverClaimSameDelivery() throws Exception {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Sub");
        sub.setUrl("http://localhost:" + serverPort + "/webhook");
        sub.setSecret(RECEIVER_SECRET);
        sub.setEventTypes(List.of("*"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        SubscriptionEntity savedSub = subscriptionRepository.save(sub);

        // Insert 20 deliveries due now
        int totalDeliveries = 20;
        List<UUID> allDeliveryIds = new ArrayList<>();
        for (int i = 0; i < totalDeliveries; i++) {
            EventEntity event = eventRepository.save(new EventEntity("idem_claim_" + i + "_" + UUID.randomUUID(), "test", null, "{}"));
            DeliveryEntity delivery = new DeliveryEntity(event.getId(), savedSub.getId());
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setNextAttemptAt(Instant.now().minusSeconds(10));
            delivery = deliveryRepository.save(delivery);
            allDeliveryIds.add(delivery.getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<List<UUID>> claimTaskWorker1 = () -> {
            latch.await();
            return claimRepository.claimDueDeliveries("worker-1", 10, 30)
                    .stream().map(DeliveryEntity::getId).toList();
        };

        Callable<List<UUID>> claimTaskWorker2 = () -> {
            latch.await();
            return claimRepository.claimDueDeliveries("worker-2", 10, 30)
                    .stream().map(DeliveryEntity::getId).toList();
        };

        Future<List<UUID>> future1 = executor.submit(claimTaskWorker1);
        Future<List<UUID>> future2 = executor.submit(claimTaskWorker2);

        latch.countDown(); // Fire both workers simultaneously

        List<UUID> claimedByWorker1 = future1.get(5, TimeUnit.SECONDS);
        List<UUID> claimedByWorker2 = future2.get(5, TimeUnit.SECONDS);

        executor.shutdown();

        // 1. Both workers claimed some work
        assertThat(claimedByWorker1).isNotEmpty();
        assertThat(claimedByWorker2).isNotEmpty();

        // 2. Both workers together claimed all 20 rows
        assertThat(claimedByWorker1.size() + claimedByWorker2.size()).isEqualTo(totalDeliveries);

        // 3. CRITICAL: The sets of claimed deliveries are completely DISJOINT (no double claims!)
        Set<UUID> intersection = new HashSet<>(claimedByWorker1);
        intersection.retainAll(claimedByWorker2);
        assertThat(intersection).isEmpty();

        // 4. In database, all rows are IN_FLIGHT and locked_by reflects the respective worker
        for (UUID id : claimedByWorker1) {
            DeliveryEntity d = deliveryRepository.findById(id).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT);
            assertThat(d.getLockedBy()).isEqualTo("worker-1");
        }
        for (UUID id : claimedByWorker2) {
            DeliveryEntity d = deliveryRepository.findById(id).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT);
            assertThat(d.getLockedBy()).isEqualTo("worker-2");
        }
    }

    private void awaitDeliveryStatus(UUID deliveryId, DeliveryStatus expectedStatus, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            DeliveryEntity d = deliveryRepository.findById(deliveryId).orElse(null);
            if (d != null && d.getStatus() == expectedStatus) {
                return;
            }
            Thread.sleep(50);
        }
        DeliveryEntity finalD = deliveryRepository.findById(deliveryId).orElse(null);
        assertThat(finalD != null ? finalD.getStatus() : null).isEqualTo(expectedStatus);
    }
}

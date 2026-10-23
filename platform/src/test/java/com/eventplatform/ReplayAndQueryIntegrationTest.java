package com.eventplatform;

import com.eventplatform.delivery.BackoffCalculator;
import com.eventplatform.delivery.DeliveryAttemptRepository;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryExecutor;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.event.EventEntity;
import com.eventplatform.event.EventRepository;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.subscription.SubscriptionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ReplayAndQueryIntegrationTest {

    private static final String API_KEY = "test-api-key-test-environment-123456";

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private BackoffCalculator backoffCalculator;

    private static HttpServer testHttpServer;
    private static int serverPort;
    private static final AtomicInteger responseCode = new AtomicInteger(500);

    @BeforeAll
    static void startHttpServer() throws IOException {
        testHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = testHttpServer.getAddress().getPort();
        testHttpServer.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int code = responseCode.get();
                byte[] responseBytes = ("{\"status\": " + code + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, responseBytes.length);
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
        responseCode.set(500);
    }

    private SubscriptionEntity createSub(String name) {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName(name);
        sub.setUrl("http://localhost:" + serverPort + "/webhook");
        sub.setSecret("whsec_replay_test_secret");
        sub.setEventTypes(List.of("*"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        return subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("Replayed DEAD delivery becomes SUCCEEDED when receiver is fixed, preserving full attempt history")
    void testReplayDeadDeliveryBecomesSucceededWithPreservedHistory() throws Exception {
        SubscriptionEntity sub = createSub("Replay Target Sub");
        EventEntity event = eventRepository.save(new EventEntity("idem_replay_" + UUID.randomUUID(), "test.replay", null, "{}"));

        DeliveryEntity delivery = deliveryRepository.save(new DeliveryEntity(event.getId(), sub.getId()));

        // Receiver returns 500 until retries are exhausted (3 attempts)
        responseCode.set(500);
        for (int i = 0; i < backoffCalculator.getMaxAttempts(); i++) {
            deliveryExecutor.executeDelivery(delivery, "worker-1");
            delivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        }

        // Delivery is now DEAD
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.getDeadAt()).isNotNull();

        // 1. Verify DLQ query endpoint lists the dead delivery
        mockMvc.perform(get("/api/v1/dlq")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(delivery.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("DEAD"));

        // 2. Fix the receiver (returns 200 OK)
        responseCode.set(200);

        // 3. Trigger replay via POST /api/v1/deliveries/{id}/replay
        mockMvc.perform(post("/api/v1/deliveries/{id}/replay", delivery.getId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.deadAt").doesNotExist());

        // Worker executes replayed delivery
        DeliveryEntity replayedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(replayedDelivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        deliveryExecutor.executeDelivery(replayedDelivery, "worker-1");

        // 4. Verify delivery is now SUCCEEDED
        DeliveryEntity finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(finalDelivery.getLockedBy()).isNull();

        // 5. Verify attempt history: 3 failed attempts + 1 successful attempt = 4 total attempts preserved!
        MvcResult attemptsResult = mockMvc.perform(get("/api/v1/deliveries/{id}/attempts", delivery.getId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode attemptsJson = objectMapper.readTree(attemptsResult.getResponse().getContentAsString());
        assertThat(attemptsJson.get(0).get("attemptNo").asInt()).isEqualTo(1);
        assertThat(attemptsJson.get(0).get("httpStatus").asInt()).isEqualTo(500);

        assertThat(attemptsJson.get(1).get("attemptNo").asInt()).isEqualTo(2);
        assertThat(attemptsJson.get(1).get("httpStatus").asInt()).isEqualTo(500);

        assertThat(attemptsJson.get(2).get("attemptNo").asInt()).isEqualTo(3);
        assertThat(attemptsJson.get(2).get("httpStatus").asInt()).isEqualTo(500);

        assertThat(attemptsJson.get(3).get("attemptNo").asInt()).isEqualTo(4);
        assertThat(attemptsJson.get(3).get("httpStatus").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("Bulk replay of all DEAD deliveries for a subscription")
    void testBulkReplayForSubscription() throws Exception {
        SubscriptionEntity sub = createSub("Bulk Replay Sub");

        // Insert 3 DEAD deliveries and 1 SUCCEEDED delivery
        for (int i = 0; i < 3; i++) {
            EventEntity event = eventRepository.save(new EventEntity("idem_bulk_" + i + "_" + UUID.randomUUID(), "test", null, "{}"));
            DeliveryEntity delivery = new DeliveryEntity(event.getId(), sub.getId());
            delivery.setStatus(DeliveryStatus.DEAD);
            delivery.setDeadAt(Instant.now());
            deliveryRepository.save(delivery);
        }

        EventEntity succeededEvent = eventRepository.save(new EventEntity("idem_succ_" + UUID.randomUUID(), "test", null, "{}"));
        DeliveryEntity succeededDelivery = new DeliveryEntity(succeededEvent.getId(), sub.getId());
        succeededDelivery.setStatus(DeliveryStatus.SUCCEEDED);
        deliveryRepository.save(succeededDelivery);

        // Bulk replay
        mockMvc.perform(post("/api/v1/subscriptions/{id}/replay-dead", sub.getId())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(sub.getId().toString()))
                .andExpect(jsonPath("$.replayedCount").value(3));

        // In DB: 3 deliveries are now PENDING, 1 is still SUCCEEDED, 0 are DEAD
        assertThat(deliveryRepository.countBySubscriptionIdAndStatus(sub.getId(), DeliveryStatus.PENDING)).isEqualTo(3);
        assertThat(deliveryRepository.countBySubscriptionIdAndStatus(sub.getId(), DeliveryStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(deliveryRepository.countBySubscriptionIdAndStatus(sub.getId(), DeliveryStatus.DEAD)).isEqualTo(0);
    }
}

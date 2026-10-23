package com.eventplatform;

import com.eventplatform.api.dto.EventIngestResponse;
import com.eventplatform.api.dto.IngestEventRequest;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.event.EventRepository;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.subscription.SubscriptionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class EventIngestIntegrationTest {

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @BeforeEach
    void setUp() {
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        subscriptionRepository.deleteAll();
    }

    private SubscriptionEntity createSub(String name, List<String> types, SubscriptionStatus status) {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName(name);
        sub.setUrl("https://example.com/receiver");
        sub.setSecret("whsec_secret1234567890abcdef");
        sub.setEventTypes(types);
        sub.setStatus(status);
        return subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("202 Accepted on first ingest vs 200 OK on duplicate with same Idempotency-Key")
    void testCreateVsDuplicateIdempotency() throws Exception {
        createSub("Wildcard Sub", List.of("*"), SubscriptionStatus.ACTIVE);

        String idempotencyKey = "key_idem_" + UUID.randomUUID();
        IngestEventRequest request = new IngestEventRequest(
                "order.created",
                "customer_1",
                objectMapper.readTree("{\"orderId\": \"ord_100\"}")
        );

        // First ingest -> 202 Accepted
        MvcResult firstResult = mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.isDuplicate").value(false))
                .andExpect(jsonPath("$.deliveriesCount").value(1))
                .andReturn();

        EventIngestResponse firstResp = objectMapper.readValue(
                firstResult.getResponse().getContentAsString(),
                EventIngestResponse.class
        );

        // Second ingest with exact same Idempotency-Key -> 200 OK
        MvcResult secondResult = mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstResp.id().toString()))
                .andExpect(jsonPath("$.isDuplicate").value(true))
                .andExpect(jsonPath("$.deliveriesCount").value(1))
                .andReturn();

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);

        // GET /events/{id} must return event and deliveries
        mockMvc.perform(get("/api/v1/events/{id}", firstResp.id())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstResp.id().toString()))
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("Wildcard '*' and specific event-type matching")
    void testWildcardAndSpecificEventTypeMatching() throws Exception {
        createSub("Wildcard Sub", List.of("*"), SubscriptionStatus.ACTIVE);
        createSub("Orders Only Sub", List.of("order.created", "order.cancelled"), SubscriptionStatus.ACTIVE);
        createSub("Payments Only Sub", List.of("payment.succeeded"), SubscriptionStatus.ACTIVE);

        IngestEventRequest orderEvent = new IngestEventRequest(
                "order.created",
                null,
                objectMapper.readTree("{\"id\": 1}")
        );

        // Event matches Wildcard and Orders Only -> 2 deliveries
        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", "idem_order_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderEvent)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveriesCount").value(2));

        IngestEventRequest userEvent = new IngestEventRequest(
                "user.registered",
                null,
                objectMapper.readTree("{\"id\": 2}")
        );

        // Event matches only Wildcard -> 1 delivery
        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", "idem_user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userEvent)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveriesCount").value(1));
    }

    @Test
    @DisplayName("Paused subscriptions are skipped during fan-out")
    void testPausedSubscriptionSkipped() throws Exception {
        createSub("Active Sub", List.of("*"), SubscriptionStatus.ACTIVE);
        createSub("Paused Sub", List.of("*"), SubscriptionStatus.PAUSED);

        IngestEventRequest request = new IngestEventRequest(
                "invoice.created",
                null,
                objectMapper.readTree("{\"amount\": 500}")
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", "idem_paused_test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveriesCount").value(1));

        assertThat(deliveryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("50 parallel concurrent identical requests create exactly ONE event and ONE set of deliveries")
    void testConcurrentDuplicateRequestsRace() throws Exception {
        createSub("Concurrent Sub A", List.of("*"), SubscriptionStatus.ACTIVE);
        createSub("Concurrent Sub B", List.of("*"), SubscriptionStatus.ACTIVE);

        int concurrency = 50;
        String sharedKey = "shared_idem_race_" + UUID.randomUUID();
        IngestEventRequest request = new IngestEventRequest(
                "stock.updated",
                "item_42",
                objectMapper.readTree("{\"sku\": \"XYZ-1\", \"qty\": 10}")
        );

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // Synchronize release of all 50 threads
                return mockMvc.perform(post("/api/v1/events")
                                .header("X-API-Key", API_KEY)
                                .header("Idempotency-Key", sharedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andReturn();
            }));
        }

        startLatch.countDown(); // Fire all 50 simultaneously

        int createdCount = 0;
        int duplicateCount = 0;
        List<UUID> returnedIds = Collections.synchronizedList(new ArrayList<>());

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(200, 202);

            EventIngestResponse resp = objectMapper.readValue(
                    result.getResponse().getContentAsString(),
                    EventIngestResponse.class
            );
            returnedIds.add(resp.id());

            if (status == 202) {
                createdCount++;
                assertThat(resp.isDuplicate()).isFalse();
            } else {
                duplicateCount++;
                assertThat(resp.isDuplicate()).isTrue();
            }
        }

        executor.shutdown();

        // Exactly one request was the initial creator (202), and the remaining 49 were duplicates (200)
        assertThat(createdCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(49);

        // Every single response returned the exact same event UUID
        UUID expectedId = returnedIds.get(0);
        assertThat(returnedIds).allMatch(id -> id.equals(expectedId));

        // In database, exactly 1 event row and exactly 2 delivery rows exist
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(2);
    }
}

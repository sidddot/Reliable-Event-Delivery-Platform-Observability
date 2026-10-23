package com.eventplatform;

import com.eventplatform.delivery.DeliveryAttemptEntity;
import com.eventplatform.delivery.DeliveryAttemptRepository;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.event.EventEntity;
import com.eventplatform.event.EventRepository;
import com.eventplatform.subscription.CircuitState;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.subscription.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DomainSchemaIntegrationTest {

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @BeforeEach
    void cleanUp() {
        deliveryAttemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        subscriptionRepository.deleteAll();
    }

    @Test
    @DisplayName("CRUD on subscriptions table: create, read, update, matching logic, delete")
    void testSubscriptionCrud() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Payment Webhook");
        sub.setUrl("https://receiver.example.com/webhooks");
        sub.setSecret("sec_live_initialsecret12345678");
        sub.setEventTypes(List.of("payment.completed", "payment.failed"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setMaxConcurrency(15);
        sub.setRateLimitPerSec(250);
        sub.setTimeoutMs(4000);
        sub.setCircuitState(CircuitState.CLOSED);

        SubscriptionEntity saved = subscriptionRepository.save(sub);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // Read
        Optional<SubscriptionEntity> fetched = subscriptionRepository.findById(saved.getId());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getName()).isEqualTo("Payment Webhook");
        assertThat(fetched.get().getEventTypes()).containsExactly("payment.completed", "payment.failed");
        assertThat(fetched.get().matchesEventType("payment.completed")).isTrue();
        assertThat(fetched.get().matchesEventType("user.created")).isFalse();

        // Update
        saved.setStatus(SubscriptionStatus.PAUSED);
        saved.setPreviousSecret("sec_live_initialsecret12345678");
        saved.setPreviousSecretExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        saved.setSecret("sec_live_rotatedsecret87654321");
        SubscriptionEntity updated = subscriptionRepository.save(saved);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(updated.getPreviousSecret()).isEqualTo("sec_live_initialsecret12345678");

        // Delete
        subscriptionRepository.delete(updated);
        assertThat(subscriptionRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("CRUD on events table: create, read, unique idempotency key constraint")
    void testEventCrudAndIdempotencyConstraint() throws Exception {
        String idempotencyKey = "idem_" + UUID.randomUUID();
        String payload = "{\"orderId\": \"ord_123\", \"total\": 450.00, \"currency\": \"USD\"}";

        EventEntity event = new EventEntity(idempotencyKey, "order.created", "customer_99", payload);
        EventEntity saved = eventRepository.save(event);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        // Read by idempotency key
        Optional<EventEntity> byKey = eventRepository.findByIdempotencyKey(idempotencyKey);
        assertThat(byKey).isPresent();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(byKey.get().getPayload()))
                .isEqualTo(mapper.readTree(payload));
        assertThat(byKey.get().getPartitionKey()).isEqualTo("customer_99");

        // Duplicate idempotency key must throw DataIntegrityViolationException
        EventEntity duplicate = new EventEntity(idempotencyKey, "order.created", "customer_99", payload);
        assertThatThrownBy(() -> eventRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CRUD on deliveries table: create, claim state transition, unique (event_id, subscription_id)")
    void testDeliveryCrudAndUniqueConstraint() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Receiver");
        sub.setUrl("http://localhost:8081/webhook");
        sub.setSecret("sec_test");
        sub.setEventTypes(List.of("*"));
        SubscriptionEntity savedSub = subscriptionRepository.save(sub);

        EventEntity event = new EventEntity("idem_del_" + UUID.randomUUID(), "test.event", null, "{}");
        EventEntity savedEvent = eventRepository.save(event);

        DeliveryEntity delivery = new DeliveryEntity(savedEvent.getId(), savedSub.getId());
        DeliveryEntity savedDelivery = deliveryRepository.save(delivery);

        assertThat(savedDelivery.getId()).isNotNull();
        assertThat(savedDelivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(savedDelivery.getAttemptCount()).isEqualTo(0);

        // Update state to IN_FLIGHT with lock
        savedDelivery.setStatus(DeliveryStatus.IN_FLIGHT);
        savedDelivery.setLockedBy("worker-alpha");
        savedDelivery.setLockedUntil(Instant.now().plus(30, ChronoUnit.SECONDS));
        savedDelivery.setAttemptCount(1);
        DeliveryEntity inFlight = deliveryRepository.save(savedDelivery);
        assertThat(inFlight.getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT);
        assertThat(inFlight.getLockedBy()).isEqualTo("worker-alpha");

        // Attempting to insert a duplicate delivery for same event & subscription must fail
        DeliveryEntity duplicateDelivery = new DeliveryEntity(savedEvent.getId(), savedSub.getId());
        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(duplicateDelivery))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CRUD on delivery_attempts table: create, snippet truncation at 1KB, ordered retrieval")
    void testDeliveryAttemptCrudAndTruncation() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setName("Attempt Sub");
        sub.setUrl("http://localhost:8081/test");
        sub.setSecret("sec_sub");
        sub.setEventTypes(List.of("*"));
        SubscriptionEntity savedSub = subscriptionRepository.save(sub);

        EventEntity event = new EventEntity("idem_att_" + UUID.randomUUID(), "test.attempt", null, "{}");
        EventEntity savedEvent = eventRepository.save(event);

        DeliveryEntity delivery = deliveryRepository.save(new DeliveryEntity(savedEvent.getId(), savedSub.getId()));

        String largeResponse = "x".repeat(2000);
        DeliveryAttemptEntity attempt1 = new DeliveryAttemptEntity(
                delivery.getId(),
                1,
                Instant.now().minus(2, ChronoUnit.SECONDS),
                150L,
                500,
                "Internal Server Error",
                largeResponse,
                "worker-1"
        );
        DeliveryAttemptEntity savedAttempt1 = deliveryAttemptRepository.save(attempt1);

        // Snippet must be truncated to 1024 chars
        assertThat(savedAttempt1.getResponseSnippet()).isNotNull();
        assertThat(savedAttempt1.getResponseSnippet().length()).isEqualTo(1024);

        DeliveryAttemptEntity attempt2 = new DeliveryAttemptEntity(
                delivery.getId(),
                2,
                Instant.now(),
                45L,
                200,
                null,
                "{\"received\": true}",
                "worker-2"
        );
        deliveryAttemptRepository.save(attempt2);

        List<DeliveryAttemptEntity> attempts = deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNoAsc(delivery.getId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getAttemptNo()).isEqualTo(1);
        assertThat(attempts.get(0).getHttpStatus()).isEqualTo(500);
        assertThat(attempts.get(1).getAttemptNo()).isEqualTo(2);
        assertThat(attempts.get(1).getHttpStatus()).isEqualTo(200);
    }
}

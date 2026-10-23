package com.eventplatform.delivery;

import com.eventplatform.api.BadRequestException;
import com.eventplatform.api.ResourceNotFoundException;
import com.eventplatform.api.dto.BulkReplayResponse;
import com.eventplatform.api.dto.DeliveryResponse;
import com.eventplatform.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final DeliveryRepository deliveryRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final String BULK_REPLAY_QUERY = """
        UPDATE deliveries
        SET status = 'PENDING',
            next_attempt_at = ?,
            locked_by = NULL,
            locked_until = NULL,
            dead_at = NULL,
            updated_at = ?
        WHERE subscription_id = ?
          AND status = 'DEAD'
        """;

    public ReplayService(DeliveryRepository deliveryRepository,
                         SubscriptionRepository subscriptionRepository,
                         JdbcTemplate jdbcTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public DeliveryResponse replayDelivery(UUID deliveryId) {
        DeliveryEntity delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + deliveryId));

        if (delivery.getStatus() != DeliveryStatus.DEAD) {
            throw new BadRequestException("Cannot replay delivery in state " + delivery.getStatus()
                    + ". Only DEAD deliveries can be replayed.");
        }

        Instant now = Instant.now();
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setNextAttemptAt(now);
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);
        delivery.setDeadAt(null);
        delivery.setUpdatedAt(now);

        DeliveryEntity saved = deliveryRepository.save(delivery);
        log.info("Delivery replayed: id={} previousAttempts={}", deliveryId, saved.getAttemptCount());
        return DeliveryResponse.fromEntity(saved);
    }

    @Transactional
    public BulkReplayResponse replayDeadDeliveriesForSubscription(UUID subscriptionId) {
        if (!subscriptionRepository.existsById(subscriptionId)) {
            throw new ResourceNotFoundException("Subscription not found: " + subscriptionId);
        }

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        int updatedCount = jdbcTemplate.update(BULK_REPLAY_QUERY, nowTs, nowTs, subscriptionId);
        log.info("Bulk replay completed for subscriptionId={}: {} deliveries reset to PENDING", subscriptionId, updatedCount);

        return new BulkReplayResponse(subscriptionId, updatedCount);
    }
}

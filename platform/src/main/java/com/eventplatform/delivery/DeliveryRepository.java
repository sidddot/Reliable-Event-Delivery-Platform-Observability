package com.eventplatform.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {

    List<DeliveryEntity> findByEventId(UUID eventId);

    Optional<DeliveryEntity> findByEventIdAndSubscriptionId(UUID eventId, UUID subscriptionId);

    List<DeliveryEntity> findBySubscriptionIdAndStatus(UUID subscriptionId, DeliveryStatus status);

    Page<DeliveryEntity> findByStatus(DeliveryStatus status, Pageable pageable);

    Page<DeliveryEntity> findBySubscriptionId(UUID subscriptionId, Pageable pageable);

    Page<DeliveryEntity> findBySubscriptionIdAndStatus(UUID subscriptionId, DeliveryStatus status, Pageable pageable);

    long countBySubscriptionIdAndStatus(UUID subscriptionId, DeliveryStatus status);

    long countByStatus(DeliveryStatus status);

    @Query("SELECT COUNT(d) FROM DeliveryEntity d WHERE d.status = 'IN_FLIGHT'")
    long countInFlight();

    @Query("SELECT COUNT(d) FROM DeliveryEntity d WHERE d.status = 'PENDING'")
    long countPending();

    @Query("SELECT COUNT(d) FROM DeliveryEntity d WHERE d.status = 'DEAD'")
    long countDead();
}

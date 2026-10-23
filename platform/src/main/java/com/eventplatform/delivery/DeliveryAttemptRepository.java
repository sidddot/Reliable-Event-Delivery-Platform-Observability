package com.eventplatform.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttemptEntity, UUID> {

    List<DeliveryAttemptEntity> findByDeliveryIdOrderByAttemptNoAsc(UUID deliveryId);

    long countByDeliveryId(UUID deliveryId);
}

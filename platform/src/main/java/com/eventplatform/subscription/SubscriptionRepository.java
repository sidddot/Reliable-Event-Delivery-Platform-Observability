package com.eventplatform.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {

    List<SubscriptionEntity> findByStatus(SubscriptionStatus status);

    @Query("SELECT s FROM SubscriptionEntity s WHERE s.status = 'ACTIVE'")
    List<SubscriptionEntity> findAllActive();
}

package com.eventplatform.delivery;

import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.ClaimRepository;
import com.eventplatform.delivery.DeliveryExecutor;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.metrics.DeliveryMetrics;
import com.eventplatform.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final ClaimRepository claimRepository;
    private final DeliveryExecutor deliveryExecutor;
    private final AppProperties appProperties;
    private final DeliveryMetrics deliveryMetrics;
    private final DeliveryRepository deliveryRepository;
    private final ExecutorService executorService;
    private final String workerId;


    public DeliveryWorker(ClaimRepository claimRepository,
                          DeliveryExecutor deliveryExecutor,
                          AppProperties appProperties,
                          DeliveryMetrics deliveryMetrics,
                          DeliveryRepository deliveryRepository) {
        this.claimRepository = claimRepository;
        this.deliveryExecutor = deliveryExecutor;
        this.appProperties = appProperties;
        this.workerId = appProperties.getWorker().getId();
        this.executorService = Executors.newFixedThreadPool(appProperties.getWorker().getConcurrency());
        this.deliveryMetrics = deliveryMetrics;
        this.deliveryRepository = deliveryRepository;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:500}")
    public void scheduledPoll() {
        if (!"worker".equalsIgnoreCase(appProperties.getRole())) {
            return;
        }
        pollOnce();
    }

    public int pollOnce() {
        int batchSize = appProperties.getWorker().getBatchSize();
        int leaseSeconds = appProperties.getWorker().getLeaseSeconds();

        List<DeliveryEntity> claimed = claimRepository.claimDueDeliveries(workerId, batchSize, leaseSeconds);
        if (claimed.isEmpty()) {
            return 0;
        }

        log.debug("Worker {} claimed {} deliveries", workerId, claimed.size());

        for (DeliveryEntity delivery : claimed) {
            executorService.submit(() -> {
                try {
                    deliveryExecutor.executeDelivery(delivery, workerId);
                } catch (Exception e) {
                    log.error("Unexpected error executing deliveryId={}: ", delivery.getId(), e);
                }
            });
        }
        // Update pending backlog gauge after processing claims
        try {
            long pending = deliveryRepository.countPending();
            deliveryMetrics.setPendingBacklog((int) pending);
        } catch (Exception e) {
            log.warn("Failed to update pending backlog metric", e);
        }

        return claimed.size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down worker {}", workerId);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public String getWorkerId() {
        return workerId;
    }
}

package com.eventplatform.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralised Micrometer metrics for the event delivery platform.
 *
 * Counters/Timers are created lazily via the MeterRegistry.
 * The class provides convenient methods called from the delivery flow.
 */
@Component
public class DeliveryMetrics {

    private final Counter eventsIngested;
    private final Counter deliveriesSucceeded;
    private final Counter deliveriesFailed;
    private final Counter deliveriesDead;
    private final Counter deliveriesRetried;
    private final Timer deliveryLatency;
    private final DistributionSummary retryAttempts;
    private final Gauge dlqSizeGauge;
    private final Gauge pendingBacklogGauge;
    private final Counter circuitOpenCount;
    private final Counter circuitHalfOpenCount;
    private final Counter circuitClosedCount;

    private final AtomicInteger dlqSize = new AtomicInteger(0);
    private final AtomicInteger pendingBacklog = new AtomicInteger(0);
    private final AtomicInteger circuitOpen = new AtomicInteger(0);
    private final AtomicInteger circuitHalfOpen = new AtomicInteger(0);
    private final AtomicInteger circuitClosed = new AtomicInteger(0);

    public DeliveryMetrics(MeterRegistry registry) {
        this.eventsIngested = Counter.builder("events.ingested.total")
                .description("Total number of events ingested via the API")
                .register(registry);

        this.deliveriesSucceeded = Counter.builder("deliveries.succeeded.total")
                .description("Total successful deliveries")
                .register(registry);
        this.deliveriesFailed = Counter.builder("deliveries.failed.total")
                .description("Total delivery attempts that failed but are retryable")
                .register(registry);
        this.deliveriesDead = Counter.builder("deliveries.dead.total")
                .description("Total deliveries moved to DLQ (dead) after exhausting retries")
                .register(registry);
        this.deliveriesRetried = Counter.builder("deliveries.retried.total")
                .description("Total times a delivery was scheduled for a retry")
                .register(registry);

        this.deliveryLatency = Timer.builder("deliveries.latency")
                .description("Latency of HTTP delivery attempts")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);

        this.retryAttempts = DistributionSummary.builder("deliveries.retry.attempts")
                .description("Number of retry attempts per delivery")
                .register(registry);

        this.dlqSizeGauge = Gauge.builder("deliveries.dlq.size", dlqSize, AtomicInteger::get)
                .description("Current size of the dead‑letter queue (deliveries with status DEAD)")
                .register(registry);

        this.pendingBacklogGauge = Gauge.builder("deliveries.pending.backlog", pendingBacklog, AtomicInteger::get)
                .description("Number of pending deliveries (status PENDING) in the queue")
                .register(registry);

        this.circuitOpenCount = Counter.builder("circuit.open.total")
                .description("Total times a subscription circuit opened")
                .register(registry);
        this.circuitHalfOpenCount = Counter.builder("circuit.half_open.total")
                .description("Total times a subscription circuit entered half‑open state")
                .register(registry);
        this.circuitClosedCount = Counter.builder("circuit.closed.total")
                .description("Total times a subscription circuit closed")
                .register(registry);
    }

    // ----- public helpers -----
    public void incEventsIngested() { eventsIngested.increment(); }

    public void recordDeliverySuccess(Duration latency) { deliveriesSucceeded.increment(); deliveryLatency.record(latency); }
    public void recordDeliveryFailure(boolean willRetry) { if (willRetry) deliveriesFailed.increment(); else deliveriesDead.increment(); }
    public void incRetried() { deliveriesRetried.increment(); }
    public void recordRetryAttempts(int attempts) { retryAttempts.record(attempts); }

    public void setDlqSize(int size) { dlqSize.set(size); }
    public void setPendingBacklog(int size) { pendingBacklog.set(size); }

    public void circuitOpened() { circuitOpen.incrementAndGet(); circuitOpenCount.increment(); }
    public void circuitHalfOpened() { circuitHalfOpen.incrementAndGet(); circuitHalfOpenCount.increment(); }
    public void circuitClosed() { circuitClosed.incrementAndGet(); circuitClosedCount.increment(); }
}

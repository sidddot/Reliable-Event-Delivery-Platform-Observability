# Metrics Documentation

This document enumerates all Micrometer metrics exposed by the **Reliable Event Delivery** platform.  They are defined in `DeliveryMetrics.java` and are automatically exported via the Spring Boot Actuator endpoint **/actuator/prometheus**.

| Metric name | Type | Description | Where it is updated |
|-------------|------|-------------|---------------------|
| `events.ingested.total` | Counter | Total number of events ingested via the API (`EventController`). | `DeliveryMetrics.incEventsIngested()` – called in `EventController` and `DeliveryExecutor`.
| `deliveries.succeeded.total` | Counter | Number of successful delivery attempts. | `DeliveryMetrics.recordDeliverySuccess()` – called when a delivery succeeds.
| `deliveries.failed.total` | Counter | Number of delivery attempts that failed but are retry‑able. | `DeliveryMetrics.recordDeliveryFailure(true)` – called for retry‑able failures.
| `deliveries.dead.total` | Counter | Deliveries moved to the dead‑letter queue after exhausting retries or non‑retryable errors. | `DeliveryMetrics.recordDeliveryFailure(false)` – called for dead deliveries.
| `deliveries.retried.total` | Counter | Number of times a delivery was scheduled for a retry. | `DeliveryMetrics.incRetried()` – called when a retry is scheduled.
| `deliveries.retry.attempts` | DistributionSummary | Number of retry attempts per delivery (value = attempt count). | `DeliveryMetrics.recordRetryAttempts(attemptNo)` – called after each retry decision.
| `deliveries.latency` | Timer | Latency of HTTP delivery attempts (ms). | `DeliveryMetrics.recordDeliverySuccess(Duration)` – records successful delivery latency.
| `deliveries.dlq.size` | Gauge | Current size of the dead‑letter queue (count of deliveries with status **DEAD**). | Updated via `DeliveryMetrics.setDlqSize(int)` – called by the worker after polling.
| `deliveries.pending.backlog` | Gauge | Number of pending deliveries (status **PENDING**) in the queue. | Updated via `DeliveryMetrics.setPendingBacklog(int)` – called after each poll.
| `circuit.open.total` | Counter | Total times any subscription circuit opened (circuit breaker triggered). | `DeliveryMetrics.circuitOpened()` – called by `CircuitBreakerService.recordFailure` when failures exceed threshold.
| `circuit.half_open.total` | Counter | Total times a circuit transitioned to half‑open (probe phase). | `DeliveryMetrics.circuitHalfOpened()` – called when an open circuit expires.
| `circuit.closed.total` | Counter | Total times a circuit closed after a successful probe. | `DeliveryMetrics.circuitClosed()` – called when a subscription recovers.

## Suggested alerting
- **High ingest rate**: Alert if `events.ingested.total` rate spikes > X events/min (tune for traffic).
- **Delivery failures**: Alert on `deliveries.failed.total` rate > Y fails/min or if the ratio `deliveries.failed.total / events.ingested.total` > Z%.
- **DLQ growth**: Alert when `deliveries.dlq.size` exceeds a threshold (e.g., 1000) or its growth rate is abnormal.
- **Circuit breaker**: Alert on rapid increase of `circuit.open.total` indicating downstream service instability.

These metrics are available at `http://<host>:<port>/actuator/prometheus` once the application is running.

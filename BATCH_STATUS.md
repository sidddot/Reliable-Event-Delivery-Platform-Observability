# Monorepo Progress Log: Reliable Event Delivery Platform

This document tracks progress, testing results, gate verifications, and known issues for each batch in accordance with project engineering standards.

## Summary Table

| Batch | Title | Status | Gate Requirement | Verification Summary |
| :--- | :--- | :--- | :--- | :--- |
| **Batch 0** | Scaffolding | COMPLETED | `docker compose up --build` gives healthy app connected to Postgres | Verified: Testcontainers unit & docker compose integration passed with UP health |
| **Batch 1** | Schema and domain | COMPLETED | Testcontainers applies migrations and CRUD on all 4 tables | Verified: DomainSchemaIntegrationTest (4/4 passed) with constraints & JSONB |
| **Batch 2** | Subscription management | COMPLETED | Controller & integration tests for all endpoints + 401 check | Verified: SubscriptionControllerIntegrationTest (4/4 passed) with 401, validation, CRUD, rotation |
| **Batch 3** | Event ingest, idempotency, fan-out | COMPLETED | 202 vs 200 duplicate, matching types, parallel race tests | Verified: EventIngestIntegrationTest (4/4 passed) with 50-thread concurrent race test |
| **Batch 4** | Delivery worker core | COMPLETED | Signed delivery to mock receiver, SUCCEEDED state, no double-claims | Verified: DeliveryWorkerIntegrationTest (2/2 passed) with HMAC verification & disjoint claims |
| **Batch 5** | Retries, backoff, DLQ, lease recovery | COMPLETED | Outcome classes, backoff bounds, DLQ transition, worker crash recovery | Verified: 10/10 tests passed (OutcomeClassifierTest, BackoffCalculatorTest, DeliveryRetryAndDlqIntegrationTest) |
| **Batch 6** | Replay and query APIs | COMPLETED | Replayed DEAD delivery becomes SUCCEEDED, attempt history preserved | Verified: ReplayAndQueryIntegrationTest (2/2 passed) with single & bulk replay and history audit |
| **Batch 7** | Isolation and resilience | PENDING | Circuit breaker, concurrency cap, Redis token bucket, partition ordering | - |
| **Batch 8** | Observability | PENDING | Micrometer, Prometheus, provisioned Grafana dashboard | - |
| **Batch 9** | Python tooling | PENDING | FastAPI mock receiver (all modes), Locust load test & report | - |
| **Batch 10** | Chaos and end-to-end suite | PENDING | Pytest e2e suite & chaos runner passing on clean compose stack | - |
| **Batch 11** | Docs, CI, polish | PENDING | Mermaid architecture, design decisions, benchmarks, GitHub Actions CI | - |

---

## Batch Logs

### Batch 0: Scaffolding
- **Planned Work**:
  - Setup Maven project with Spring Boot 3.3.4, Java 21.
  - Setup Dockerfile (multi-stage) and `docker-compose.yml` with Postgres 16.
  - Setup Flyway migration support and Actuator `/actuator/health`.
  - Add `Makefile`, `.env.example`, `.gitignore`.
- **Created/Modified Files**:
  - `platform/pom.xml`
  - `platform/Dockerfile`
  - `platform/src/main/resources/application.yml`
  - `platform/src/main/resources/application-test.yml`
  - `platform/src/main/resources/db/migration/V0__init.sql`
  - `platform/src/main/java/com/eventplatform/EventPlatformApplication.java`
  - `platform/src/test/java/com/eventplatform/EventPlatformApplicationTests.java`
  - `docker-compose.yml`
  - `Makefile`
  - `.env.example`
  - `.gitignore`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=EventPlatformApplicationTests`: PASSED (PostgreSQL 16 container started, Flyway applied `V0__init.sql`, context loaded).
  - `docker compose up --build -d`: PASSED (Both `event-platform-postgres` and `event-platform-api` reached `healthy` status; `GET /actuator/health` returned HTTP 200 with `status: UP` and database component `PostgreSQL isValid()`).
- **Results & Gate Status**:
  - **GATE PASSED**: Fully reproducible, healthy stack running Java 21, Spring Boot 3.3.4, PostgreSQL 16.

### Batch 1: Schema and Domain
- **Planned Work**:
  - Flyway migration `V1__init_schema.sql` defining `subscriptions`, `events`, `deliveries`, and `delivery_attempts`.
  - Added domain enums: `SubscriptionStatus`, `CircuitState`, `DeliveryStatus`.
  - Added JPA Entities: `SubscriptionEntity`, `EventEntity`, `DeliveryEntity`, `DeliveryAttemptEntity`.
  - Added Repositories: `SubscriptionRepository`, `EventRepository`, `DeliveryRepository`, `DeliveryAttemptRepository`.
  - Partial indexes for worker claim query (`status = 'PENDING'`) and lease recovery (`status = 'IN_FLIGHT'`).
  - Constraint tests for unique idempotency key, unique `(event_id, subscription_id)`, and 1KB snippet truncation.
- **Created/Modified Files**:
  - `platform/src/main/resources/db/migration/V1__init_schema.sql`
  - `platform/src/main/java/com/eventplatform/subscription/SubscriptionStatus.java`
  - `platform/src/main/java/com/eventplatform/subscription/CircuitState.java`
  - `platform/src/main/java/com/eventplatform/subscription/SubscriptionEntity.java`
  - `platform/src/main/java/com/eventplatform/subscription/SubscriptionRepository.java`
  - `platform/src/main/java/com/eventplatform/event/EventEntity.java`
  - `platform/src/main/java/com/eventplatform/event/EventRepository.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryStatus.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryEntity.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryRepository.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryAttemptEntity.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryAttemptRepository.java`
  - `platform/src/test/java/com/eventplatform/DomainSchemaIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=DomainSchemaIntegrationTest`: PASSED (4 tests run, 0 failures, 0 errors).
    - `testSubscriptionCrud`: PASSED
    - `testEventCrudAndIdempotencyConstraint`: PASSED
    - `testDeliveryCrudAndUniqueConstraint`: PASSED
    - `testDeliveryAttemptCrudAndTruncation`: PASSED
- **Results & Gate Status**:
  - **GATE PASSED**: Schema migrations applied cleanly; full CRUD and database-level constraints validated.

### Batch 2: Subscription Management
- **Planned Work**:
  - `ApiKeyAuthFilter` protecting `/api/v1/**` while bypassing actuator and Swagger.
  - `GlobalExceptionHandler` and `ErrorResponse` formatting standardized JSON validation and error details.
  - `SubscriptionController` and `SubscriptionService` with full CRUD (`GET`, `POST`, `PATCH`, `DELETE`).
  - Secret rotation endpoint (`POST /subscriptions/{id}/rotate-secret`) preserving `previous_secret` with expiration.
  - OpenAPI/Swagger UI configuration with `ApiKeyAuth` scheme.
- **Created/Modified Files**:
  - `platform/src/main/java/com/eventplatform/config/AppProperties.java`
  - `platform/src/main/java/com/eventplatform/config/ApiKeyAuthFilter.java`
  - `platform/src/main/java/com/eventplatform/config/OpenApiConfig.java`
  - `platform/src/main/java/com/eventplatform/api/ErrorResponse.java`
  - `platform/src/main/java/com/eventplatform/api/BadRequestException.java`
  - `platform/src/main/java/com/eventplatform/api/ResourceNotFoundException.java`
  - `platform/src/main/java/com/eventplatform/api/GlobalExceptionHandler.java`
  - `platform/src/main/java/com/eventplatform/api/dto/CreateSubscriptionRequest.java`
  - `platform/src/main/java/com/eventplatform/api/dto/UpdateSubscriptionRequest.java`
  - `platform/src/main/java/com/eventplatform/api/dto/RotateSecretRequest.java`
  - `platform/src/main/java/com/eventplatform/api/dto/SubscriptionResponse.java`
  - `platform/src/main/java/com/eventplatform/subscription/SubscriptionService.java`
  - `platform/src/main/java/com/eventplatform/api/SubscriptionController.java`
  - `platform/src/test/java/com/eventplatform/SubscriptionControllerIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=SubscriptionControllerIntegrationTest`: PASSED (4 tests run, 0 failures, 0 errors).
    - `testAuthenticationRequired`: PASSED (Missing and invalid `X-API-Key` returns 401).
    - `testValidationErrors`: PASSED (Malformed URLs and empty eventTypes return 400 with details).
    - `testSubscriptionCrudLifecycle`: PASSED (Create, Read, List, Update/Pause, Delete).
    - `testRotateSecret`: PASSED (Dual-secret rollover with previousSecret expiration).
- **Results & Gate Status**:
  - **GATE PASSED**: All subscription endpoints, auth filter, validation, and secret rotation verified.

### Batch 3: Event Ingest, Idempotency, and Fan-Out
- **Planned Work**:
  - `IngestService` implementing atomic single-transaction fan-out.
  - Required `Idempotency-Key` header with database unique constraint.
  - Concurrent duplicate race recovery returning HTTP 200 without duplicate creation.
  - Event matching logic: wildcard `*`, exact match, and skipped paused subscriptions.
  - `EventController` exposing `POST /api/v1/events` (202 on create, 200 on duplicate) and `GET /api/v1/events/{id}`.
- **Created/Modified Files**:
  - `platform/src/main/java/com/eventplatform/api/dto/IngestEventRequest.java`
  - `platform/src/main/java/com/eventplatform/api/dto/EventIngestResponse.java`
  - `platform/src/main/java/com/eventplatform/api/dto/DeliverySummaryResponse.java`
  - `platform/src/main/java/com/eventplatform/api/dto/EventWithDeliveriesResponse.java`
  - `platform/src/main/java/com/eventplatform/event/IngestService.java`
  - `platform/src/main/java/com/eventplatform/api/EventController.java`
  - `platform/src/test/java/com/eventplatform/EventIngestIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=EventIngestIntegrationTest`: PASSED (4 tests run, 0 failures, 0 errors).
    - `testCreateVsDuplicateIdempotency`: PASSED (202 on create, 200 on duplicate with identical eventId).
    - `testWildcardAndSpecificEventTypeMatching`: PASSED (Wildcard received all, specific received matching types).
    - `testPausedSubscriptionSkipped`: PASSED (Paused subscriptions skipped at ingest).
    - `testConcurrentDuplicateRequestsRace`: PASSED (50 concurrent parallel threads created exactly 1 event and 2 deliveries; 1 returned 202, 49 returned 200).
- **Results & Gate Status**:
  - **GATE PASSED**: Atomic single-transaction fan-out and concurrent idempotency guarantees proven.

### Batch 4: Delivery Worker Core
- **Planned Work**:
  - `HmacSigner` computing HMAC-SHA256 signatures with dual-signing support.
  - `ClaimRepository` using atomic `FOR UPDATE OF d SKIP LOCKED` CTE with timed lease renewal.
  - `DeliveryExecutor` handling HTTP calls with timeouts, attempt logging, and `SUCCEEDED` status update.
  - `DeliveryWorker` scheduled polling loop with bounded thread pool.
  - Python FastAPI mock receiver in `tools/mock_receiver/` supporting HMAC verification and mock modes.
  - Added `receiver-healthy` service to `docker-compose.yml`.
- **Created/Modified Files**:
  - `platform/src/main/java/com/eventplatform/signing/HmacSigner.java`
  - `platform/src/main/java/com/eventplatform/delivery/ClaimRepository.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryExecutor.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryWorker.java`
  - `tools/mock_receiver/main.py`
  - `tools/mock_receiver/requirements.txt`
  - `tools/mock_receiver/Dockerfile`
  - `docker-compose.yml`
  - `platform/src/test/java/com/eventplatform/DeliveryWorkerIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=DeliveryWorkerIntegrationTest`: PASSED (2 tests run, 0 failures, 0 errors).
    - `testEndToEndSuccessfulSignedDelivery`: PASSED (Worker claimed, signed, dispatched HTTP, receiver validated HMAC, delivery reached SUCCEEDED).
    - `testTwoWorkersNeverClaimSameDelivery`: PASSED (Two concurrent workers claimed 20 deliveries with 0 overlap; disjoint isolation verified).
- **Results & Gate Status**:
  - **GATE PASSED**: Delivery worker claim, HMAC signing, attempt persistence, and multi-worker isolation validated.

### Batch 5: Retries, Backoff, DLQ, and Lease Recovery
- **Planned Work**:
  - `DeliveryOutcome` enum and `OutcomeClassifier` classifying 2xx, retryable (5xx, 408, 429, network), and non-retryable 4xx.
  - `BackoffCalculator` implementing AWS Full Jitter exponential backoff and `Retry-After` header parsing.
  - `LeaseSweeper` recovering expired in-flight leases back to `PENDING`.
  - Updated `DeliveryExecutor` with retry scheduling, DLQ transitions to `DEAD`, `dead_at`, and `last_error`.
  - Added mock receiver modes to `docker-compose.yml`: `receiver-slow`, `receiver-flaky`, `receiver-down`.
- **Created/Modified Files**:
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryOutcome.java`
  - `platform/src/main/java/com/eventplatform/delivery/OutcomeClassifier.java`
  - `platform/src/main/java/com/eventplatform/delivery/BackoffCalculator.java`
  - `platform/src/main/java/com/eventplatform/delivery/LeaseSweeper.java`
  - `platform/src/main/java/com/eventplatform/delivery/DeliveryExecutor.java`
  - `docker-compose.yml`
  - `platform/src/test/java/com/eventplatform/OutcomeClassifierTest.java`
  - `platform/src/test/java/com/eventplatform/BackoffCalculatorTest.java`
  - `platform/src/test/java/com/eventplatform/DeliveryRetryAndDlqIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest="OutcomeClassifierTest,BackoffCalculatorTest,DeliveryRetryAndDlqIntegrationTest"`: PASSED (10 tests run, 0 failures, 0 errors).
    - `OutcomeClassifierTest`: 3/3 passed (2xx success, 5xx/408/429/network retryable, 4xx non-retryable).
    - `BackoffCalculatorTest`: 3/3 passed (Jitter bounds, Retry-After header clamping, exhaustion check).
    - `DeliveryRetryAndDlqIntegrationTest`: 4/4 passed.
      - `testRetryableFailureSchedulesRetry`: PASSED (500 error scheduled retry with delay and stayed PENDING).
      - `testNonRetryableFailureGoesStraightToDead`: PASSED (404 error went directly to DEAD).
      - `testExhaustedRetriesLandInDead`: PASSED (Exhausted retries landed in DEAD with dead_at and last_error).
      - `testWorkerCrashLeaseExpiryRecovery`: PASSED (Expired worker lease claimed by healthy worker and completed to SUCCEEDED).
- **Results & Gate Status**:
  - **GATE PASSED**: Full Jitter backoff, DLQ state transitions, outcome classifications, and worker crash recovery validated.

### Batch 6: Replay and Query APIs
- **Planned Work**:
  - `ReplayService` supporting single delivery replay (`POST /deliveries/{id}/replay`) and bulk replay (`POST /subscriptions/{id}/replay-dead`).
  - Query endpoints: `GET /deliveries` (filtered by status, subscriptionId, eventId, with pagination), `GET /deliveries/{id}/attempts` (chronological attempt logs).
  - DLQ query endpoint: `GET /dlq?subscriptionId=` returning paginated DEAD deliveries.
- **Created/Modified Files**:
  - `platform/src/main/java/com/eventplatform/api/dto/DeliveryResponse.java`
  - `platform/src/main/java/com/eventplatform/api/dto/DeliveryAttemptResponse.java`
  - `platform/src/main/java/com/eventplatform/api/dto/BulkReplayResponse.java`
  - `platform/src/main/java/com/eventplatform/delivery/ReplayService.java`
  - `platform/src/main/java/com/eventplatform/api/DeliveryController.java`
  - `platform/src/main/java/com/eventplatform/api/DlqController.java`
  - `platform/src/test/java/com/eventplatform/ReplayAndQueryIntegrationTest.java`
  - `BATCH_STATUS.md`
- **Tests Executed**:
  - `mvn test -Dtest=ReplayAndQueryIntegrationTest`: PASSED (2 tests run, 0 failures, 0 errors).
    - `testReplayDeadDeliveryBecomesSucceededWithPreservedHistory`: PASSED (Dead delivery replayed, completed to SUCCEEDED, all 4 attempts [3 failures + 1 success] preserved).
    - `testBulkReplayForSubscription`: PASSED (3 DEAD deliveries bulk replayed to PENDING, while SUCCEEDED deliveries remained untouched).
- **Results & Gate Status**:
  - **GATE PASSED**: Single and bulk DLQ replay, delivery queries, and immutable attempt audit preservation validated.







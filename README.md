# Reliable Event Delivery Platform

This repository contains a **Spring Boot** based reliable event delivery service with:
- PostgreSQL persistence
- Redis for rate‑limiting / caching
- Micrometer metrics exposed via **Actuator** (`/actuator/prometheus`)
- Docker & Docker‑Compose for local development and CI
- Comprehensive integration tests

## Prerequisites
- Docker Engine (or Docker Desktop) >= 20.10
- Docker‑Compose >= 2.0
- Java 17 (if you want to run the app directly from Maven)

## Quick start with Docker Compose
```bash
# From the project root
docker compose up --build -d   # builds images and starts postgres, redis, the API and mock receivers
```
The API will be reachable at **`http://localhost:8080`**.

### Health & metrics endpoints
- Health: `GET http://localhost:8080/actuator/health`
- Prometheus metrics: `GET http://localhost:8080/actuator/prometheus`

### Using the API
```bash
# Ingest an event (replace the payload as needed)
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-key-123" \
  -d '{"eventType":"order.created","partitionKey":"order-1","payload":{"orderId":123,"amount":45.6}}'
```
The response will include an `id` and a flag indicating whether the event was a duplicate.

## Running locally (without Docker)
```bash
# Clone the repo and cd into platform
cd platform
# Build and run tests
mvn clean install
# Start the app (requires a local PostgreSQL and Redis instance)
java -jar target/reliable-event-delivery-1.0.0-SNAPSHOT.jar
```
Make sure the environment variables in `src/main/resources/application.yml` point to your local services.

## CI / CD (GitHub Actions)
A minimal CI pipeline is provided in `.github/workflows/ci.yml`. It:
1. Sets up JDK 17 and caches Maven dependencies.
2. Runs the full test suite (`mvn test`).
3. Builds the Docker images (`docker compose build`).
4. Executes a health‑check against the API container to ensure the Actuator endpoint is up.

The workflow runs on every push and pull‑request to `main`.

## Project structure
```
SEProject/
├─ .github/workflows/ci.yml   # CI pipeline
├─ docs/metrics.md            # Metrics documentation (auto‑generated)
├─ platform/                  # Spring Boot application
│   ├─ src/main/java/...      # Java source
│   ├─ src/main/resources/   # application.yml, etc.
│   └─ Dockerfile             # Container image for the API
└─ docker-compose.yml         # Orchestrates postgres, redis, API, mock receivers
```

## FAQ
- **Where are the metrics?** → Exposed at `/actuator/prometheus` and defined in `com.eventplatform.metrics.DeliveryMetrics`.
- **How to see dead‑letter queue size?** → Metric `deliveries.dlq.size`.
- **What if I need to change DB credentials?** → Override env vars `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` when running the container or locally.

---
*Happy coding!*

## Observability

The platform exposes Micrometer metrics at `http://localhost:8080/actuator/prometheus`.

When you run the stack with Docker Compose, two additional services are started:

- **Prometheus** (`http://localhost:9090`) scrapes the `/actuator/prometheus` endpoint.
- **Grafana** (`http://localhost:3000`) is pre‑configured with a datasource pointing to Prometheus and a dashboard that visualises the main metrics.

You can log in to Grafana with the default credentials **admin / admin**. The dashboard is available under *Dashboards → Manage → Event Metrics*.

### Launch the full stack
```bash
# From the project root
docker compose up --build -d   # starts postgres, redis, API, Prometheus and Grafana
```

- The API will be reachable at `http://localhost:8080`.
- Prometheus UI: `http://localhost:9090`.
- Grafana UI: `http://localhost:3000`.

## Stopping the stack
```bash
docker compose down -v   # stops containers and removes volumes
```

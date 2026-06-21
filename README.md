# RateAtlas · TaxIQ (`rateatlas-api`)

> Versioned tax bracket API with observability, caching, rate limiting, and circuit breaking.
>
> Part of the [RateAtlas](https://github.com/CHA0sTIG3R/RateAtlas) stack.

![CI](https://github.com/CHA0sTIG3R/rateatlas-api/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)

## Overview

`TaxIQ` is a Spring Boot 3 REST API that serves versioned U.S. federal income tax bracket data. It is the primary backend for the RateAtlas platform, providing tax calculation, simulation, and dataset freshness endpoints backed by PostgreSQL and Redis.

On startup, TaxIQ bootstraps its database from the latest CSV archived in S3 if no data is present.

Ongoing data updates are pushed directly by the `BracketForge` ingest Lambda after detecting IRS page changes.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Implementation Status](#implementation-status)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [API Endpoints](#api-endpoints)
- [Observability](#observability)
- [Testing](#testing)
- [Continuous Integration](#continuous-integration)
- [Deployment](#deployment)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Versioned tax data** — brackets stored and queryable by year across all four filing statuses.
- **Dataset freshness endpoint** — exposes IRS page update date, last ingest timestamp, and computed freshness state. Freshness gauge primed on every startup so it survives deploys.
- **S3 bootstrap** — automatically seeds the database from S3 on the first startup if no data is present.
- **Redis caching** — bracket lookups, tax calculations, and no-tax year checks cached with 1-hour TTL via manual `RedisTemplate`. Cache evicted automatically on ingestion upload.
- **Rate limiting** — per-IP and per-API-key rate limiting via Bucket4j token bucket backed by Redis. 100 requests/minute with greedy refill. Returns `429` with `Retry-After` header when exceeded.
- **Circuit breaker** — Resilience4j circuit breakers on all DB-hitting service methods. Cache-first fallback on open circuit — serves cached data if available, returns `503` if not.
- **OpenTelemetry tracing** — distributed traces exported to Grafana Cloud Tempo via OTel Java agent. Custom spans on all key business operations with span attributes for year, status, and metric.
- **Prometheus metrics** — exposes request latency histograms, tax calculation counters, simulation counters, ingest run/skip counts, and data freshness gauges.
- **Grafana Cloud observability** — metrics scraped by Grafana Alloy on EC2 and forwarded to Grafana Cloud hosted Prometheus.
- **Spring Security** — `/actuator/prometheus` endpoint protected with HTTP Basic auth.
- **Flyway migrations** — all schema changes are version-controlled and applied automatically on startup.
- **OpenAPI / Swagger UI** — interactive API docs at `/swagger-ui/index.html`.
- **GitHub Actions CI/CD** — test, build, push to ECR, and deploy to EC2 on every merge to `main`.
- **Testcontainers integration tests** — real PostgreSQL and Redis containers spun up in CI for integration coverage.

---

## Implementation Status

| Feature                          | Status        |
|----------------------------------|---------------|
| Tax bracket CRUD endpoints       | ✅ Implemented |
| Tax calculation endpoints        | ✅ Implemented |
| Bulk simulation endpoint         | ✅ Implemented |
| Dataset freshness endpoint       | ✅ Implemented |
| S3 bootstrap on startup          | ✅ Implemented |
| Flyway schema migrations         | ✅ Implemented |
| OpenAPI / Swagger UI             | ✅ Implemented |
| Spring Boot Actuator             | ✅ Implemented |
| Prometheus metrics               | ✅ Implemented |
| Grafana Cloud observability      | ✅ Implemented |
| OpenTelemetry tracing            | ✅ Implemented |
| Spring Security (actuator auth)  | ✅ Implemented |
| Redis caching                    | ✅ Implemented |
| Rate limiting / throttling       | ✅ Implemented |
| Circuit breaker                  | ✅ Implemented |
| GitHub Actions CI/CD             | ✅ Implemented |
| Testcontainers integration tests | ✅ Implemented |
| OAuth2 / API key auth            | 🔲 Planned    |

> ✅ = Complete & tested  🔲 = Not yet implemented

---

## Project Structure
```
src/main/java/com/project/marginal/tax/calculator/
├── bootstrap/
│   └── TaxDataBootstrapper.java     # Seeds DB from S3 on startup if empty; primes freshness gauge
├── config/
│   ├── AwsS3Config.java             # S3Client bean wired from AWS_REGION
│   ├── RateLimitConfig.java         # Bucket4j + Lettuce Redis proxy manager
│   ├── RedisConfig.java             # RedisTemplate with Jackson serialization
│   ├── Resilience4jConfig.java      # Circuit breaker bean registration
│   └── SecurityConfig.java         # Spring Security — protects /actuator/prometheus
├── controller/
│   ├── DatasetController.java       # GET /api/v1/datasets/latest
│   └── TaxController.java          # Tax bracket and calculation endpoints
├── dto/
│   ├── DatasetFreshnessResponse.java
│   └── ...
├── entity/
│   ├── IngestMetadata.java
│   └── TaxRate.java
├── exception/
│   └── GlobalExceptionHandler.java  # Handles CallNotPermittedException → 503
├── filter/
│   └── RateLimitFilter.java         # Per-IP and per-API-key rate limiting
├── metrics/
│   └── MetricsService.java          # Prometheus counters and gauges
├── repository/
│   ├── IngestMetadataRepository.java
│   └── TaxRateRepository.java
├── service/
│   ├── CacheService.java            # RedisTemplate wrapper with transparent fallback
│   ├── DatasetService.java          # Freshness computation and metadata reads
│   └── TaxService.java             # Tax calculation and simulation logic with circuit breakers
└── utility/
    └── CsvImportUtils.java          # CSV parsing for S3 bootstrap and uploads
```

---

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker (for local development and Testcontainers)

---

## Installation

1. Clone this repository:
```bash
git clone https://github.com/CHA0sTIG3R/rateatlas-api.git
cd rateatlas-api
```

2. Copy the example env file and fill in your values:
```bash
cp .env.example .env.local
```

3. Start local PostgreSQL and Redis via Docker:
```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
```

4. Build the project:
```bash
./mvnw clean package -DskipTests
```

5. Run the application:
```bash
./mvnw spring-boot:run
```

---

## Configuration

Key environment variables (see `.env.example` for a full template):

| Variable                      | Required | Description                                              |
|-------------------------------|----------|----------------------------------------------------------|
| `SPRING_DATASOURCE_URL`       | ✅        | JDBC URL for PostgreSQL                                  |
| `SPRING_DATASOURCE_USERNAME`  | ✅        | Database username                                        |
| `SPRING_DATASOURCE_PASSWORD`  | ✅        | Database password                                        |
| `REDIS_URL`                   | ✅        | Redis connection URL                                     |
| `AWS_REGION`                  | ✅        | AWS region for S3 client                                 |
| `AWS_ACCESS_KEY_ID`           | ✅        | AWS credentials for S3 bootstrap                         |
| `AWS_SECRET_ACCESS_KEY`       | ✅        | AWS credentials for S3 bootstrap                         |
| `TAX_S3_BUCKET`               | ✅        | S3 bucket containing the tax CSV                         |
| `TAX_S3_KEY`                  | ✅        | S3 object key for the tax CSV                            |
| `PROMETHEUS_SCRAPE_USERNAME`  | ✅        | HTTP Basic username for `/actuator/prometheus`           |
| `PROMETHEUS_SCRAPE_PASSWORD`  | ✅        | HTTP Basic password for `/actuator/prometheus`           |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | ✅        | Grafana Cloud Tempo OTLP endpoint                        |
| `OTEL_EXPORTER_OTLP_HEADERS`  | ✅        | Authorization header for OTLP export                     |
| `APP_INGEST_API_KEY`          | ⬜        | API key for `/api/v1/tax/upload`. Leave empty to disable |

> Production secrets are managed via environment variables injected during deployment. Never commit `.env` files.

---

## API Endpoints

Live docs: `https://api.ratesatlas.com/swagger-ui/index.html`

| Method | Path                        | Description                                                              |
|--------|-----------------------------|--------------------------------------------------------------------------|
| POST   | `/api/v1/tax/upload`        | Ingest CSV upload (authenticated, Lambda only)                           |
| GET    | `/api/v1/tax/years`         | All available tax years                                                  |
| GET    | `/api/v1/tax/filing-status` | Supported filing statuses (S, MFJ, MFS, HOH)                             |
| GET    | `/api/v1/tax/rate`          | Tax brackets for a given year and optional filing status                 |
| POST   | `/api/v1/tax/breakdown`     | Single-scenario tax breakdown `{ year, status, income }`                 |
| GET    | `/api/v1/tax/summary`       | Total tax, average rate, bracket count for a given year/status           |
| GET    | `/api/v1/tax/history`       | Year-over-year metrics (TOP_RATE, MIN_RATE, AVERAGE_RATE, BRACKET_COUNT) |
| POST   | `/api/v1/tax/simulate`      | Bulk tax breakdowns across multiple income scenarios                     |
| GET    | `/api/v1/datasets/latest`   | Dataset freshness metadata                                               |
| GET    | `/actuator/health`          | Health check (includes circuit breaker states: CLOSED/OPEN/HALF_OPEN)    |
| GET    | `/actuator/prometheus`      | Prometheus metrics (basic auth required)                                 |

### `GET /api/v1/datasets/latest` — Example Response
```json
{
  "latestAvailableTaxYear": 2024,
  "irsPageLastUpdated": "2026-02-20",
  "lastIngestedAt": "2026-02-21T08:34:12Z",
  "freshnessState": "FRESH",
  "sourceUrl": "https://www.irs.gov/filing/federal-income-tax-rates-and-brackets"
}
```

`freshnessState` is computed dynamically:
- `FRESH` — latest available year is current year minus one or newer
- `STALE` — latest available year is older than current year minus one

---

## Observability

TaxIQ exposes a Prometheus-compatible metrics endpoint at `/actuator/prometheus` (basic auth required).

**Custom metrics:**

| Metric                             | Type    | Description                                      |
|------------------------------------|---------|--------------------------------------------------|
| `rateatlas_tax_calculations_total` | Counter | Tax calculation requests served                  |
| `rateatlas_tax_simulations_total`  | Counter | Bulk simulation requests served                  |
| `rateatlas_data_freshness_days`    | Gauge   | Days since last ingest                           |
| `rateatlas_ingest_run_count`       | Gauge   | Total ingest runs recorded in metadata           |
| `rateatlas_ingest_skip_count`      | Gauge   | Total skipped runs (no IRS page change detected) |

Metrics are scraped by **Grafana Alloy** running on the same EC2 instance and forwarded to **Grafana Cloud** hosted Prometheus. The live dashboard tracks API uptime, p95 request latency by route, data freshness, and calculation throughput.

**Distributed tracing:**

All key service methods are instrumented with the OTel Java agent and custom `@WithSpan` annotations. Traces are exported to **Grafana Cloud Tempo** and include span attributes for tax year, filing status, and metric type. Trace the full request lifecycle from HTTP ingress through DB queries in the Grafana Explore view.

---

## Testing

```bash
./mvnw test    # runs all tests (unit + Testcontainers integration tests)
```

Integration tests spin up real PostgreSQL and Redis containers via Testcontainers — no external services are required.

---

## Continuous Integration

GitHub Actions handles:

- `.github/workflows/ci.yml` — runs all tests on every push and pull request
- `.github/workflows/deploy.yml` — builds Docker image, pushes to AWS ECR, deploys to EC2 via SSM (on `main` only)

Use `[skip ci]` in your commit message to bypass pipelines for documentation-only changes.

---

## Deployment

Deployments are fully automated via GitHub Actions on every merge to `main`:

1. Docker image is built and tagged with the commit SHA
2. Image is pushed to **AWS ECR**
3. An SSM `AWS-RunShellScript` command is sent to EC2 instances tagged `Service=tax-api`
4. The EC2 instance pulls the new image and restarts via `docker compose up -d --force-recreate`
5. The workflow polls the SSM invocation result and fails the pipeline if the deployment command exits non-zero

Manual deploys can be triggered at any time via the `workflow_dispatch` input in the Actions tab.

---

## Contributing

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/name`
3. Commit your changes: `git commit -m "Add feature"`
4. Push and open a PR targeting `main`

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)

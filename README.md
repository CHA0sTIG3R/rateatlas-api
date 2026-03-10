# RateAtlas · TaxIQ (`rateatlas-api`)

> Versioned tax bracket API with observability, freshness tracking, and CI/CD.
>
> Part of the [RateAtlas](../README.md) stack.

## Overview

`TaxIQ` is a Spring Boot 3 REST API that serves versioned U.S. federal income tax bracket data. It is the primary backend for the RateAtlas platform, providing tax calculation, simulation, and dataset freshness endpoints backed by PostgreSQL.

On startup, TaxIQ bootstraps its database from the latest CSV archived in S3 if no data is present. Ongoing data updates are pushed directly by the `BracketForge` ingest Lambda after detecting IRS page changes.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Implementation Status](#implementation-status)
- [Project Structure](#project-structure)
- [Installation](#installation)
- [Configuration](#configuration)
- [API Endpoints](#api-endpoints)
- [Observability](#observability)
- [Testing](#testing)
- [Continuous Integration](#continuous-integration)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Versioned tax data** — brackets stored and queryable by year across all four filing statuses.
- **Dataset freshness endpoint** — exposes IRS page update date, last ingest timestamp, and computed freshness state.
- **S3 bootstrap** — automatically seeds the database from S3 on first startup if no data is present.
- **Prometheus metrics** — exposes request latency histograms, tax calculation counters, simulation counters, ingest run/skip counts, and data freshness gauges.
- **Grafana Cloud observability** — metrics scraped by Grafana Alloy on EC2 and forwarded to Grafana Cloud hosted Prometheus.
- **Spring Security** — `/actuator/prometheus` endpoint protected with HTTP Basic auth.
- **Flyway migrations** — all schema changes version-controlled and applied automatically on startup.
- **OpenAPI / Swagger UI** — interactive API docs at `/swagger-ui/index.html`.
- **GitHub Actions CI/CD** — test, build, push to ECR, and deploy to EC2 on every merge to `main`.
- **Testcontainers integration tests** — real PostgreSQL container spun up in CI for integration coverage.

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
| Spring Security (actuator auth)  | ✅ Implemented |
| GitHub Actions CI/CD             | ✅ Implemented |
| Testcontainers integration tests | ✅ Implemented |
| Redis caching                    | 🔲 Planned    |
| Rate limiting / throttling       | 🔲 Planned    |
| Circuit breaker                  | 🔲 Planned    |
| OAuth2 / API key auth            | 🔲 Planned    |

> ✅ = Complete & tested  🔲 = Not yet implemented

---

## Project Structure
```
src/main/java/com/project/marginal/tax/calculator/
├── bootstrap/
│   └── TaxDataBootstrapper.java    # Seeds DB from S3 on startup if empty
├── config/
│   └── SecurityConfig.java         # Spring Security — protects /actuator/prometheus
├── controller/
│   ├── DatasetController.java      # GET /api/v1/datasets/latest
│   └── TaxController.java          # Tax bracket and calculation endpoints
├── dto/
│   ├── DatasetFreshnessResponse.java
│   └── ...
├── entity/
│   ├── IngestMetadata.java
│   └── TaxRate.java
├── exception/
│   └── GlobalExceptionHandler.java
├── metrics/
│   └── MetricsService.java         # Prometheus counters and gauges
├── repository/
│   ├── IngestMetadataRepository.java
│   └── TaxRateRepository.java
├── service/
│   ├── DatasetService.java         # Freshness computation and metadata reads
│   └── TaxService.java             # Tax calculation and simulation logic
└── utility/
    └── CsvImportUtils.java         # CSV parsing for S3 bootstrap and uploads
```

---

## Installation

1. Clone this repository:
```bash
   git clone https://github.com/CHA0sTIG3R/rateatlas-api.git
   cd rateatlas-api
```

1. Ensure you have Java 17+ and Maven installed.

2. Start a local PostgreSQL instance (or use Docker):
```bash
   docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
```

1. Build the project:
```bash
   ./mvnw clean package -DskipTests
```

---

## Configuration

Key environment variables (copy `.env.example` → `.env.local` for local Docker runs):
```ini
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/rateatlas
SPRING_DATASOURCE_USERNAME=rateatlas
SPRING_DATASOURCE_PASSWORD=secret

# AWS (for S3 bootstrap)
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
S3_BUCKET=your-s3-bucket-name
S3_KEY=history.csv

# Ingest API key (authenticates pushes from BracketForge Lambda)
APP_INGEST_API_KEY=your-shared-secret

# Prometheus scrape auth
PROMETHEUS_SCRAPE_USERNAME=your-scrape-username
PROMETHEUS_SCRAPE_PASSWORD=your-scrape-password
```

> Production secrets are managed via environment variables injected at deploy time. Never commit `.env` files.

---

## API Endpoints

Live docs: `https://api.ratesatlas.com/swagger-ui/index.html`

| Method | Path                        | Description                                                    |
|--------|-----------------------------|----------------------------------------------------------------|
| POST   | `/api/v1/tax/upload`        | Ingest CSV upload (authenticated, Lambda only)                 |
| GET    | `/api/v1/tax/years`         | All available tax years                                        |
| GET    | `/api/v1/tax/filing-status` | Supported filing statuses (S, MFJ, MFS, HOH)                   |
| GET    | `/api/v1/tax/rate`          | Tax brackets for a given year and optional filing status       |
| POST   | `/api/v1/tax/breakdown`     | Single-scenario tax breakdown `{ year, status, income }`       |
| GET    | `/api/v1/tax/summary`       | Total tax, average rate, bracket count for a given year/status |
| GET    | `/api/v1/tax/history`       | Year-over-year metrics (TOP_RATE, AVERAGE_RATE, COUNT)         |
| POST   | `/api/v1/tax/simulate`      | Bulk tax breakdowns across multiple income scenarios           |
| GET    | `/api/v1/datasets/latest`   | Dataset freshness metadata                                     |
| GET    | `/actuator/health`          | Health check                                                   |
| GET    | `/actuator/prometheus`      | Prometheus metrics (basic auth required)                       |

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

| Metric                              | Type    | Description                                      |
|-------------------------------------|---------|--------------------------------------------------|
| `rateatlas_tax_calculations_total`  | Counter | Tax calculation requests served                  |
| `rateatlas_tax_simulations_total`   | Counter | Bulk simulation requests served                  |
| `rateatlas_data_freshness_days`     | Gauge   | Days since last ingest                           |
| `rateatlas_ingest_run_count`        | Gauge   | Total ingest runs recorded in metadata           |
| `rateatlas_ingest_skip_count`       | Gauge   | Total skipped runs (no IRS page change detected) |

Metrics are scraped by **Grafana Alloy** running on the same EC2 instance and forwarded to **Grafana Cloud** hosted Prometheus. The live dashboard tracks API uptime, p95 request latency, data freshness, and calculation throughput.

---

## Testing
```bash
./mvnw test        # unit tests only
./mvnw verify      # unit + Testcontainers integration tests
```

Integration tests spin up a real PostgreSQL container via Testcontainers — no external DB required.

---

## Continuous Integration

GitHub Actions handles:

- `.github/workflows/ci.yml` — runs unit and integration tests on every push, uploads coverage to Codecov
- `.github/workflows/deploy.yml` — builds Docker image, pushes to AWS ECR, deploys to EC2 via SSM (on `main` only)

Use `[skip ci]` in your commit message to bypass pipelines for documentation-only changes.

---

## Contributing

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/name`
3. Commit your changes: `git commit -m "Add feature"`
4. Push and open a PR targeting `main`

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)


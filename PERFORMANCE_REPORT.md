# RateAtlas API — Performance Report

**Generated:** March 14, 2026
**API:** `https://api.ratesatlas.com`
**Tool:** k6 v0.55.0
**Environment:** 5 virtual users, 1m45s duration (30s ramp-up, 1m hold, 15s ramp-down), 3s think time between iterations

---

## Test Methodology

Each test iteration hit all five endpoints in sequence:

| Endpoint                | Method | Parameters                                             |
|-------------------------|--------|--------------------------------------------------------|
| `/api/v1/tax/years`     | GET    | —                                                      |
| `/api/v1/tax/rate`      | GET    | `year=2024&status=S`                                   |
| `/api/v1/tax/summary`   | GET    | `year=2024&status=S`                                   |
| `/api/v1/tax/history`   | GET    | `status=S&metric=TOP_RATE&startYear=2000&endYear=2024` |
| `/api/v1/tax/breakdown` | POST   | `{ year: 2024, status: "S", income: 75000 }`           |

Two scenarios were measured:

- **Baseline**: Redis cache flushed before test. All requests served directly from PostgreSQL.
- **Cached**: Redis warm from prior run. Bracket lookups, calculations, and no-tax year checks served from Redis Cloud.

---

## Results Summary

| Metric             | Baseline (DB only) | Cached (Redis warm) | Improvement    |
|--------------------|--------------------|---------------------|----------------|
| **p50 latency**    | 40.96ms            | 33.92ms             | **−17.2%**     |
| **p90 latency**    | 58.17ms            | 42.84ms             | **−26.3%**     |
| **p95 latency**    | 67.70ms            | 46.93ms             | **−30.7%**     |
| **avg latency**    | 45.34ms            | 35.05ms             | **−22.7%**     |
| **max latency**    | 797.09ms           | 78.52ms             | **−90.2%**     |
| **error rate**     | 0.00%              | 0.00%               | —              |
| **throughput**     | 6.16 req/s         | 6.37 req/s          | +3.4%          |
| **total requests** | 665                | 670                 | —              |

---

## Key Findings

### 1. Cache eliminates worst-case latency spikes
The most dramatic improvement is in max latency: **797ms → 79ms, a 90% reduction**. The elevated baseline max (vs prior runs) reflects the expanded cold-cache surface — `listYears`, `getSummary`, and `getHistory` now all perform their initial DB reads and write to Redis on first hit, causing slightly larger first-request spikes. Once warm, those methods serve from cache and the max collapses to 79ms.

### 2. p95 improved by 30.7%
p95 dropped from **67.70ms → 46.93ms** under identical load — a larger improvement than previously measured. This is the direct result of fixing write-only cache patterns in `listYears`, `getSummary`, and `getHistory`, which were writing to Redis but never reading back from it, causing every request to hit the DB.

### 3. Average latency down 22.7%
Average dropped from **45.34ms → 35.05ms**, with the median tightening from 40.96ms → 33.92ms. The narrowing gap between avg and median indicates more consistent response times with fewer outliers — consistent with a warm cache serving the majority of requests.

### 4. Zero errors across both runs
Both scenarios maintained a **0.00% error rate** across 1,335 total requests, confirming stability of the rate limiter configuration (per-API-key buckets, 100 req/min), circuit breaker (CLOSED state throughout), and Redis Cloud connectivity.

### 5. Observed single-request cache impact
Manual curl measurements before load testing captured the direct cache impact per endpoint:

| Endpoint                     | First hit (cold) | Second hit (cached) | Speedup      |
|------------------------------|------------------|---------------------|--------------|
| `GET /api/v1/tax/rate`       | 565ms            | 174ms               | **3.2×**     |
| `POST /api/v1/tax/breakdown` | 277ms → 216ms    | 65ms → 50ms         | **3.5–4.3×** |

The breakdown endpoint improvement after caching `isNoTaxYear()` dropped from **277ms → 50ms**, a **5.5× speedup**.

---

## Observability — Trace Evidence

The `existsById` bottleneck was identified via OpenTelemetry distributed tracing before caching was implemented:

```
POST /api/v1/tax/breakdown — 193ms total
└── tax.calculate.breakdown (136ms)
    └── NoIncomeTaxYearRepository.existsById (105ms)  ← bottleneck
    └── TaxRateRepository.findByYearAndStatus (10ms)
```

After consolidating `noTaxYear` lookups into a single shared `noTaxYears:all` Redis key (a `HashSet<Integer>` populated once per cache TTL):
```
POST /api/v1/tax/breakdown — ~50ms total
└── tax.calculate.breakdown
    └── Redis GET noTaxYears:all (<1ms)
    └── Redis GET calc:2024:S:75000.0 (<1ms)
```

---

## Infrastructure

| Component     | Technology                                          | Role                               |
|---------------|-----------------------------------------------------|------------------------------------|
| API           | Spring Boot 3, Java 17, EC2 (t2.micro)              | Request serving                    |
| Database      | PostgreSQL 15, AWS RDS                              | Primary data store                 |
| Cache         | Redis 8.4.0, Redis Cloud free tier (30MB)           | Response caching, rate limit state |
| Observability | Prometheus + Grafana Alloy + Grafana Cloud          | Metrics                            |
| Tracing       | OTel Java agent + Grafana Cloud Tempo               | Distributed traces                 |
| Protection    | Bucket4j token bucket, Resilience4j circuit breaker | Rate limiting, fault tolerance     |

---

## Caching Strategy

| Cache Key Pattern                         | TTL    | Contents                                              |
|-------------------------------------------|--------|-------------------------------------------------------|
| `brackets:{year}:{status}`                | 1 hour | Tax bracket lists by year and filing status           |
| `calc:{year}:{status}:{income}`           | 1 hour | Full tax breakdown responses                          |
| `noTaxYears:all`                          | 1 hour | `HashSet<Integer>` of all no-income-tax years         |
| `years:all`                               | 1 hour | Full list of available tax years                      |
| `summary:{year}:{status}`                 | 1 hour | Tax summary responses                                 |
| `history:{status}:{metric}:{start}:{end}` | 1 hour | Year-over-year metric history                         |

Cache is evicted automatically on `POST /api/v1/tax/upload` (ingest push from BracketForge Lambda).

---

## Current Performance Metrics Achievements

- Reduced p95 API latency by **31% under load** (68ms → 47ms) via Redis caching
- Eliminated worst-case latency spikes by **90%** (797ms → 79ms max) once cache is warm
- Achieved **47ms p95 response times** on warm cache across all five endpoints
- Identified and resolved 105ms `existsById` bottleneck via OTel distributed tracing, consolidated into single `noTaxYears:all` cache key shared across all methods, achieving **5.5× speedup** on tax calculation endpoint
- Fixed write-only cache patterns in `listYears`, `getSummary`, and `getHistory` — methods were storing results in Redis but never reading them back
- Eliminated N+1 query pattern in `getHistory` by grouping `findByStatus` results in-memory instead of calling `findByYearAndStatus` per year
- Maintained **0% error rate** across 1,335 requests under concurrent load with rate limiting and circuit breaker active

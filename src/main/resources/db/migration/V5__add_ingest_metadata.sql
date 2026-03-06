-- Schema owned here for migration management via Flyway.
-- Runtime writes are owned by the ingest layer (rateatlas-ingest).
-- Read by API via GET /v1/datasets/latest
CREATE TABLE ingest_metadata (
    id                      SERIAL PRIMARY KEY,
    last_seen_page_update   DATE NOT NULL,
    last_ingested_at        TIMESTAMPTZ NOT NULL,
    freshness_state         VARCHAR(10) NOT NULL CHECK (freshness_state IN ('FRESH', 'STALE'))
);
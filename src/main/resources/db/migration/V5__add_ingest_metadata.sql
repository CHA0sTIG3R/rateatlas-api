-- Schema owned here for migration management via Flyway.
-- Runtime writes are owned by the ingest layer (rateatlas-ingest).
-- Read by API via GET /v1/datasets/latest
CREATE TABLE ingest_metadata (
    id                      INTEGER PRIMARY KEY DEFAULT 1,
    last_seen_page_update   DATE NOT NULL,
    last_ingested_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT single_row CHECK (id = 1)
);
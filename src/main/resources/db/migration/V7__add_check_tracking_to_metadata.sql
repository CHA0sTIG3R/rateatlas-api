-- V7__add_check_tracking_to_metadata.sql
-- Expand step of an expand/contract change for pipeline-liveness tracking.
--
-- Columns are added NULLABLE on purpose: the pre-V7 ingestor upserts ingest_metadata
-- without listing these columns, and Postgres checks NOT NULL against the proposed
-- INSERT row before resolving ON CONFLICT. NOT NULL is applied in a later migration,
-- once the ingestor writes these columns on every run.
--
--   last_checked_at   - last SUCCESSFUL source check (ingest or unchanged-page skip); drives liveness
--   last_attempted_at - last check attempt of any outcome; proves the scheduler is firing
--   last_check_status - outcome of the most recent attempt
CREATE TYPE ingest_check_status AS ENUM ('SUCCESS', 'FAILED');

ALTER TABLE ingest_metadata
    ADD COLUMN last_checked_at   TIMESTAMPTZ,
    ADD COLUMN last_attempted_at TIMESTAMPTZ,
    ADD COLUMN last_check_status ingest_check_status;

-- Every past ingest was a successful check (and an attempt) at that moment, so
-- last_ingested_at is a true lower bound. Unrecorded skips since then are not recoverable.
UPDATE ingest_metadata
   SET last_checked_at   = last_ingested_at,
       last_attempted_at = last_ingested_at,
       last_check_status = 'SUCCESS';

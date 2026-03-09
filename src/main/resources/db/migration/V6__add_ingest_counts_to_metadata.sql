-- V6__add_ingest_counts_to_metadata.sql
ALTER TABLE ingest_metadata
    ADD COLUMN ingest_run_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN ingest_skip_count INTEGER NOT NULL DEFAULT 0;
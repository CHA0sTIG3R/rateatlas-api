package com.project.marginal.tax.calculator.entity;

/**
 * Outcome of the most recent ingest source check.
 * <p>
 * Mirrors the Postgres enum type {@code ingest_check_status} created in V7.
 * Any outcome that is not a valid source check (fetch error, unparseable page date,
 * DB write error) is recorded as {@link #FAILED}.
 * </p>
 */
public enum IngestCheckStatus {
    SUCCESS,
    FAILED
}

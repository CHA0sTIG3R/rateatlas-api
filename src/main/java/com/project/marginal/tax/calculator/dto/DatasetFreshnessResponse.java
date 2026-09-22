package com.project.marginal.tax.calculator.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Builder
@Getter
public class DatasetFreshnessResponse {
    private Integer latestAvailableTaxYear;
    private LocalDate irsPageLastUpdated;
    private OffsetDateTime lastIngestedAt;

    /**
     * Tax-year currency: "FRESH" when {@link #latestAvailableTaxYear} is at least
     * (current year - 1), "STALE" otherwise. This says nothing about whether the
     * ingest pipeline itself has recently re-checked the IRS source — see
     * {@link #ingestSignalFreshnessState} for that.
     */
    private String freshnessState;  // "FRESH" or "STALE"

    /**
     * IRS signal freshness: "FRESH" when the ingestor has recorded a successful
     * run within the configured staleness window, "STALE" when it has not, and
     * "UNKNOWN" when no ingest has ever completed. This can disagree with
     * {@link #freshnessState} — e.g. the tax year can still be current while the
     * ingest pipeline itself has stalled, or vice versa.
     */
    private String ingestSignalFreshnessState;  // "FRESH", "STALE", or "UNKNOWN"

    private String sourceUrl;
}
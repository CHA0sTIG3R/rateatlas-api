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
    private String freshnessState;  // "FRESH" or "STALE"
    private String sourceUrl;
}
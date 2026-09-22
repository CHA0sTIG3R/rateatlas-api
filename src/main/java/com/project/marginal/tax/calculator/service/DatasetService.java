package com.project.marginal.tax.calculator.service;

import com.project.marginal.tax.calculator.dto.DatasetFreshnessResponse;
import com.project.marginal.tax.calculator.entity.IngestMetadata;
import com.project.marginal.tax.calculator.metrics.MetricsService;
import com.project.marginal.tax.calculator.repository.IngestMetadataRepository;
import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class DatasetService {

    private static final String IRS_SOURCE_URL =
        "https://www.irs.gov/filing/federal-income-tax-rates-and-brackets";

    /**
     * The IRS republishes tax brackets roughly once a year; allow generous slack
     * before treating a quiet ingest pipeline as stale.
     */
    private static final long INGEST_SIGNAL_STALE_AFTER_DAYS = 400;

    private final IngestMetadataRepository metadataRepo;
    private final TaxRateRepository taxRateRepo;
    private final MetricsService metricsService;

    public DatasetFreshnessResponse getLatestDataset() {
        IngestMetadata metadata = metadataRepo.findById(1)
                .orElseThrow(() -> new IllegalStateException("No ingest metadata found"));

        OffsetDateTime lastIngestedAt = metadata.getLastIngestedAt();
        String ingestSignalFreshnessState = resolveIngestSignalFreshnessState(lastIngestedAt);

        Integer latestYear = taxRateRepo.findMaxYear()
            .orElseThrow(() -> new IllegalStateException("No tax data found"));

        int expectedYear = Year.now().getValue() - 1;
        String freshnessState = latestYear < expectedYear ? "STALE" : "FRESH";

        return DatasetFreshnessResponse.builder()
            .latestAvailableTaxYear(latestYear)
            .irsPageLastUpdated(metadata.getLastSeenPageUpdate())
            .lastIngestedAt(lastIngestedAt)
            .freshnessState(freshnessState)
            .ingestSignalFreshnessState(ingestSignalFreshnessState)
            .sourceUrl(IRS_SOURCE_URL)
            .build();
    }

    private String resolveIngestSignalFreshnessState(OffsetDateTime lastIngestedAt) {
        if (lastIngestedAt == null) {
            return "UNKNOWN";
        }
        LocalDate lastIngestedDate = lastIngestedAt.toLocalDate();
        metricsService.updateDataFreshness(lastIngestedDate);
        long daysSinceIngest = ChronoUnit.DAYS.between(lastIngestedDate, LocalDate.now());
        return daysSinceIngest > INGEST_SIGNAL_STALE_AFTER_DAYS ? "STALE" : "FRESH";
    }
}
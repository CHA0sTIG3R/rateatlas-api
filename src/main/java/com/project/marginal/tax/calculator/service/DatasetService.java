package com.project.marginal.tax.calculator.service;

import com.project.marginal.tax.calculator.dto.DatasetFreshnessResponse;
import com.project.marginal.tax.calculator.entity.IngestMetadata;
import com.project.marginal.tax.calculator.metrics.MetricsService;
import com.project.marginal.tax.calculator.repository.IngestMetadataRepository;
import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Year;

@Service
@RequiredArgsConstructor
public class DatasetService {

    private static final String IRS_SOURCE_URL =
        "https://www.irs.gov/filing/federal-income-tax-rates-and-brackets";

    private final IngestMetadataRepository metadataRepo;
    private final TaxRateRepository taxRateRepo;
    private final MetricsService metricsService;

    public DatasetFreshnessResponse getLatestDataset() {
        IngestMetadata metadata = metadataRepo.findById(1)
                .orElseThrow(() -> new IllegalStateException("No ingest metadata found"));

        if (metadata.getLastIngestedAt() != null) {
            metricsService.updateDataFreshness(metadata.getLastIngestedAt().toLocalDate());
        }

        Integer latestYear = taxRateRepo.findMaxYear()
            .orElseThrow(() -> new IllegalStateException("No tax data found"));

        int expectedYear = Year.now().getValue() - 1;
        String freshnessState = latestYear < expectedYear ? "STALE" : "FRESH";

        return DatasetFreshnessResponse.builder()
            .latestAvailableTaxYear(latestYear)
            .irsPageLastUpdated(metadata.getLastSeenPageUpdate())
            .lastIngestedAt(metadata.getLastIngestedAt())
            .freshnessState(freshnessState)
            .sourceUrl(IRS_SOURCE_URL)
            .build();
    }
}
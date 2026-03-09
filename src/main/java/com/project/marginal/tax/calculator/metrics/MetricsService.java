package com.project.marginal.tax.calculator.metrics;

import com.project.marginal.tax.calculator.entity.IngestMetadata;
import com.project.marginal.tax.calculator.repository.IngestMetadataRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {
    private final Counter taxCalculationCounter;
    private final Counter simulateBulkCounter;
    private final AtomicLong daysSinceLastIngest = new AtomicLong(0);

    public MetricsService(MeterRegistry registry,
                          IngestMetadataRepository metadataRepo) {

        this.taxCalculationCounter = Counter.builder("rateatlas.tax.calculations")
                .description("Number of tax breakdown calculations served")
                .register(registry);

        this.simulateBulkCounter = Counter.builder("rateatlas.tax.simulations")
                .description("Number of bulk simulation requests served")
                .register(registry);

        Gauge.builder("rateatlas.data.freshness.days", daysSinceLastIngest, AtomicLong::get)
                .description("Days since last successful ingest")
                .register(registry);

        Gauge.builder("rateatlas.ingest.run.count", metadataRepo,
                        repo -> repo.findById(1).map(IngestMetadata::getIngestRunCount).orElse(0))
                .description("Total successful ingest runs")
                .register(registry);

        Gauge.builder("rateatlas.ingest.skip.count", metadataRepo,
                        repo -> repo.findById(1).map(IngestMetadata::getIngestSkipCount).orElse(0))
                .description("Total skipped ingest runs")
                .register(registry);
    }

    public void recordTaxCalculation() { taxCalculationCounter.increment(); }
    public void recordSimulateBulk() { simulateBulkCounter.increment(); }

    public void updateDataFreshness(LocalDate lastIngestedDate) {
        long days = ChronoUnit.DAYS.between(lastIngestedDate, LocalDate.now());
        daysSinceLastIngest.set(days);
    }
}

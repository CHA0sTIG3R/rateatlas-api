package com.project.marginal.tax.calculator.service;

import com.project.marginal.tax.calculator.dto.DatasetFreshnessResponse;
import com.project.marginal.tax.calculator.entity.IngestMetadata;
import com.project.marginal.tax.calculator.metrics.MetricsService;
import com.project.marginal.tax.calculator.repository.IngestMetadataRepository;
import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DatasetServiceTest {

    private IngestMetadataRepository metadataRepo;
    private TaxRateRepository taxRateRepo;
    private MetricsService metricsService;
    private DatasetService service;

    @BeforeEach
    public void setUp() {
        metadataRepo = Mockito.mock(IngestMetadataRepository.class);
        taxRateRepo = Mockito.mock(TaxRateRepository.class);
        metricsService = Mockito.mock(MetricsService.class);
        service = new DatasetService(metadataRepo, taxRateRepo, metricsService);
    }

    private IngestMetadata metadataWith(LocalDate lastSeenPageUpdate, OffsetDateTime lastIngestedAt) {
        IngestMetadata metadata = new IngestMetadata();
        metadata.setId(1);
        metadata.setLastSeenPageUpdate(lastSeenPageUpdate);
        metadata.setLastIngestedAt(lastIngestedAt);
        metadata.setIngestRunCount(1);
        metadata.setIngestSkipCount(0);
        return metadata;
    }

    @Test
    public void getLatestDataset_currentTaxYearAndRecentIngest_isFreshOnBothAxes() {
        int currentTaxYear = Year.now().getValue() - 1;
        OffsetDateTime recentIngest = OffsetDateTime.now(ZoneOffset.UTC).minusDays(5);
        when(metadataRepo.findById(1)).thenReturn(Optional.of(
                metadataWith(LocalDate.now().minusDays(5), recentIngest)));
        when(taxRateRepo.findMaxYear()).thenReturn(Optional.of(currentTaxYear));

        DatasetFreshnessResponse response = service.getLatestDataset();

        assertEquals("FRESH", response.getFreshnessState());
        assertEquals("FRESH", response.getIngestSignalFreshnessState());
        assertEquals(currentTaxYear, response.getLatestAvailableTaxYear());
        verify(metricsService).updateDataFreshness(recentIngest.toLocalDate());
    }

    @Test
    public void getLatestDataset_outdatedTaxYear_isStaleOnTaxYearAxis() {
        int outdatedYear = Year.now().getValue() - 5;
        when(metadataRepo.findById(1)).thenReturn(Optional.of(
                metadataWith(LocalDate.now(), OffsetDateTime.now(ZoneOffset.UTC))));
        when(taxRateRepo.findMaxYear()).thenReturn(Optional.of(outdatedYear));

        DatasetFreshnessResponse response = service.getLatestDataset();

        assertEquals("STALE", response.getFreshnessState());
        assertEquals("FRESH", response.getIngestSignalFreshnessState());
    }

    @Test
    public void getLatestDataset_ingestPipelineStalled_isStaleOnSignalAxisEvenIfTaxYearCurrent() {
        // The tax year can remain current while the ingest pipeline itself has
        // stopped re-checking the IRS source — the two freshness axes must be
        // able to disagree instead of being conflated into one field.
        int currentTaxYear = Year.now().getValue() - 1;
        OffsetDateTime staleIngest = OffsetDateTime.now(ZoneOffset.UTC).minusDays(500);
        when(metadataRepo.findById(1)).thenReturn(Optional.of(
                metadataWith(LocalDate.now().minusDays(500), staleIngest)));
        when(taxRateRepo.findMaxYear()).thenReturn(Optional.of(currentTaxYear));

        DatasetFreshnessResponse response = service.getLatestDataset();

        assertEquals("FRESH", response.getFreshnessState());
        assertEquals("STALE", response.getIngestSignalFreshnessState());
    }

    @Test
    public void getLatestDataset_neverIngested_signalStateIsUnknownAndGaugeNotTouched() {
        int currentTaxYear = Year.now().getValue() - 1;
        when(metadataRepo.findById(1)).thenReturn(Optional.of(
                metadataWith(null, null)));
        when(taxRateRepo.findMaxYear()).thenReturn(Optional.of(currentTaxYear));

        DatasetFreshnessResponse response = service.getLatestDataset();

        assertEquals("UNKNOWN", response.getIngestSignalFreshnessState());
        verify(metricsService, never()).updateDataFreshness(any());
    }

    @Test
    public void getLatestDataset_missingIngestMetadata_throwsIllegalState() {
        when(metadataRepo.findById(1)).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.getLatestDataset());
        assertEquals("No ingest metadata found", ex.getMessage());
    }

    @Test
    public void getLatestDataset_missingTaxData_throwsIllegalState() {
        when(metadataRepo.findById(1)).thenReturn(Optional.of(
                metadataWith(LocalDate.now(), OffsetDateTime.now(ZoneOffset.UTC))));
        when(taxRateRepo.findMaxYear()).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.getLatestDataset());
        assertEquals("No tax data found", ex.getMessage());
    }
}

package com.project.marginal.tax.calculator.service;

import com.project.marginal.tax.calculator.dto.*;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import com.project.marginal.tax.calculator.entity.NoIncomeTaxYear;
import com.project.marginal.tax.calculator.entity.TaxRate;
import com.project.marginal.tax.calculator.metrics.MetricsService;
import com.project.marginal.tax.calculator.repository.NoIncomeTaxYearRepository;
import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.project.marginal.tax.calculator.utility.NumberFormatUtils.percentFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;


public class TaxServiceTest {

    private TaxRateRepository repo;
    private NoIncomeTaxYearRepository noTaxRepo;
    private TaxService service;

    @BeforeEach
    public void setUp() {
        repo = Mockito.mock(TaxRateRepository.class);
        noTaxRepo = Mockito.mock(NoIncomeTaxYearRepository.class);
        MetricsService metricsService = Mockito.mock(MetricsService.class);
        CacheService cacheService = Mockito.mock(CacheService.class);
        service = new TaxService(repo, noTaxRepo, metricsService, cacheService);
    }

    @Test
    public void testListYears() {
        TaxRate tr1 = new TaxRate();
        tr1.setYear(2020);
        TaxRate tr2 = new TaxRate();
        tr2.setYear(2021);
        when(repo.findAll()).thenReturn(List.of(tr1, tr2));

        List<Integer> years = service.listYears();
        assertEquals(List.of(2020, 2021), years);
    }

    @Test
    public void listYears_includesBracketAndNoTaxYears_sortedDistinct() {
        when(repo.findAll()).thenReturn(List.of(
                new TaxRate(2020, FilingStatus.S, 0f,BigDecimal.ZERO, BigDecimal.ZERO),
                new TaxRate(2021, FilingStatus.MFJ, 0f, BigDecimal.ZERO, BigDecimal.ZERO)
        ));
        when(noTaxRepo.findAll()).thenReturn(List.of(new NoIncomeTaxYear(2019)));
        List<Integer> years = service.listYears();
        assertEquals(List.of(2019, 2020, 2021), years);
    }

    @Test
    public void getRates_invalidYear_throws() {
        int tooOld = 1800;
        assertThrows(IllegalArgumentException.class, () -> service.getRates(tooOld, null));
    }

    @Test
    public void getFilingStatus_returnsEnumMap() {
        Map<String, String> statuses = service.getFilingStatus();
        assertTrue(statuses.containsKey(FilingStatus.S.name()));
        assertEquals("Single", statuses.get("S"));
    }


    @Test
    public void testGetRatesByYear() {
        TaxRate tr = new TaxRate(2021, FilingStatus.S, 0.10f, new BigDecimal("0"), new BigDecimal("50000"));
        when(repo.findByYear(2021)).thenReturn(List.of(tr));

        List<TaxRateDto> dtos = service.getTaxRateByYear(2021);
        assertEquals(1, dtos.size());
        TaxRateDto dto = dtos.get(0);
        assertEquals(2021, dto.getYear());
        assertEquals(FilingStatus.S, dto.getFilingStatus());
    }

    @Test
    public void getRates_noTaxYear_returnsNoIncomeTaxDto() {
        int year = 1895;
        when(noTaxRepo.existsById(year)).thenReturn(true);
        when(noTaxRepo.findById(year)).thenReturn(Optional.of(new NoIncomeTaxYear(year)));
        List<TaxRateDto> dtos = service.getRates(year, FilingStatus.S);
        assertEquals(1, dtos.size());
        TaxRateDto dto = dtos.get(0);
        assertEquals(year, dto.getYear());
        assertEquals(FilingStatus.S, dto.getFilingStatus());
        assertEquals("$0.00", dto.getRangeStart());
        assertEquals("No Upper Limit", dto.getRangeEnd());
        assertEquals("0%", dto.getRate());
        assertTrue(dto.getMessage().contains("No income tax"));
    }

    @Test
    public void getRates_nullStatus_returnsAllStatuses() {
        int year = 2021;
        TaxRate r1 = new TaxRate(year, FilingStatus.S,  0.1f, BigDecimal.ZERO, BigDecimal.TEN);
        when(noTaxRepo.existsById(year)).thenReturn(false);
        when(repo.findByYear(year)).thenReturn(List.of(r1));
        List<TaxRateDto> dtos = service.getRates(year, null);
        assertEquals(1, dtos.size());
        assertEquals(FilingStatus.S, dtos.get(0).getFilingStatus());
    }

    @Test
    public void getRates_withStatus_returnsFiltered() {
        int year = 2021;
        TaxRate r1 = new TaxRate(year, FilingStatus.MFJ, 0.1f, BigDecimal.ZERO, BigDecimal.TEN);
        when(noTaxRepo.existsById(year)).thenReturn(false);
        when(repo.findByYearAndStatus(year, FilingStatus.MFJ)).thenReturn(List.of(r1));
        List<TaxRateDto> dtos = service.getRates(year, FilingStatus.MFJ);
        assertEquals(1, dtos.size());
        assertEquals(FilingStatus.MFJ, dtos.get(0).getFilingStatus());
    }


    @Test
    public void testCalculateTaxBreakdownInvalidYear() {
        TaxInput input = new TaxInput(1800, FilingStatus.S, "50000");
        Exception ex = assertThrows(IllegalArgumentException.class, () -> service.calculateTaxBreakdown(input));
        assertTrue(ex.getMessage().contains("Invalid year"));
    }

    @Test
    public void testCalculateTaxBreakdown() {
        // Set up a simple scenario with a single tax bracket.
        TaxInput input = new TaxInput(2021, FilingStatus.S, "50000");

        TaxRate tr = new TaxRate();
        tr.setYear(2021);
        tr.setStatus(FilingStatus.S);
        tr.setRate(0.10f);
        tr.setRangeStart(new BigDecimal("0"));
        tr.setRangeEnd(new BigDecimal("50000"));

        when(repo.findByYearAndStatusAndRangeStartLessThan(eq(2021), eq(FilingStatus.S), any())).thenReturn(List.of(tr));
        TaxPaidResponse response = service.calculateTaxBreakdown(input);
        assertTrue(response.getTotalTaxPaid().contains("5,000"));
    }

    @Test
    public void getSummary_invalidYear_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getSummary(1700, FilingStatus.S));
    }

    @Test
    public void getSummary_noTaxYear_returnsNoIncomeTaxSummary() {
        int year = 2024;
        when(noTaxRepo.existsById(year)).thenReturn(true);
        when(noTaxRepo.findById(year)).thenReturn(Optional.of(new NoIncomeTaxYear(year)));
        TaxSummaryResponse resp = service.getSummary(year, FilingStatus.S);
        assertEquals(year, resp.year());
        assertEquals(FilingStatus.S, resp.status());
        assertEquals(0, resp.bracketCount());
        assertEquals("No Income Tax", resp.averageRate());
        assertEquals(BigDecimal.ZERO, resp.minThreshold());
        assertEquals(BigDecimal.ZERO, resp.maxThreshold());
    }

    @Test
    public void getSummary_normalYear_returnsCorrectSummary() {
        int year = 2022;
        when(noTaxRepo.existsById(year)).thenReturn(false);
        TaxRate t1 = new TaxRate(year, FilingStatus.S, 0.10f, BigDecimal.ZERO, new BigDecimal("5000"));
        TaxRate t2 = new TaxRate(year, FilingStatus.S, 0.20f, new BigDecimal("5000"), new BigDecimal("10000"));
        when(repo.findByYearAndStatus(year, FilingStatus.S)).thenReturn(List.of(t1, t2));

        TaxSummaryResponse resp = service.getSummary(year, FilingStatus.S);
        assertEquals(year, resp.year());
        assertEquals(FilingStatus.S, resp.status());
        assertEquals(2, resp.bracketCount());
        assertEquals("15%", resp.averageRate());
        assertEquals(2, resp.bracketCount());
        assertEquals(BigDecimal.ZERO, resp.minThreshold());
        assertEquals(new BigDecimal("10000"), resp.maxThreshold());
        assertTrue(resp.averageRate().endsWith("%"));
    }

    @Test
    public void testGetHistoryTopRate() {
        TaxRate r2020a = new TaxRate(2020, FilingStatus.S, 0.10f, BigDecimal.ZERO, new BigDecimal("50000"));
        TaxRate r2020b = new TaxRate(2020, FilingStatus.S, 0.15f, new BigDecimal("50000"), new BigDecimal("100000"));
        TaxRate r2021  = new TaxRate(2021, FilingStatus.S, 0.20f, BigDecimal.ZERO, new BigDecimal("75000"));

        when(repo.findByStatus(FilingStatus.S)).thenReturn(List.of(r2020a, r2020b, r2021));
        when(repo.findByYearAndStatus(2020, FilingStatus.S)).thenReturn(List.of(r2020a, r2020b));
        when(repo.findByYearAndStatus(2021, FilingStatus.S)).thenReturn(List.of(r2021));

        List<YearMetric> metrics = service.getHistory(FilingStatus.S, Metric.TOP_RATE, 2020, 2021);
        assertEquals(2, metrics.size());

        YearMetric m2020 = metrics.get(0);
        assertEquals(2020, m2020.getYear());
        assertEquals("TOP_RATE", m2020.getMetric());
        assertEquals(percentFormat(0.15f), m2020.getValue());

        YearMetric m2021 = metrics.get(1);
        assertEquals(2021, m2021.getYear());
        assertEquals("TOP_RATE", m2021.getMetric());
        assertEquals(percentFormat(0.20f), m2021.getValue());
    }

    @Test
    public void testGetHistoryUnsupportedMetricThrows() {
        when(repo.findByStatus(FilingStatus.S)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.getHistory(FilingStatus.S, Metric.valueOf("new metric"), 2020, 2021));
    }

        @Test
    public void calculateTaxBreakdown_yearBelowData_throws() {
        TaxInput input = new TaxInput(1800, FilingStatus.S, "50000");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.calculateTaxBreakdown(input));
        assertTrue(ex.getMessage().contains("Invalid year"));
    }

    @Test
    public void calculateTaxBreakdown_yearAboveData_throws() {
        TaxInput input = new TaxInput(3000, FilingStatus.S, "50000");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.calculateTaxBreakdown(input));
        assertTrue(ex.getMessage().contains("Invalid year"));
    }

    @Test
    public void calculateTaxBreakdown_negativeIncome_throws() {
        TaxInput input = new TaxInput(2021, FilingStatus.S, "-1000");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.calculateTaxBreakdown(input));
        assertTrue(ex.getMessage().toLowerCase().contains("income"));
    }

    @Test
    public void calculateTaxBreakdown_zeroIncome_returnsNoTax() {

        TaxRate tr = new TaxRate(2021, FilingStatus.S, 0.0f, BigDecimal.ZERO, BigDecimal.ZERO);
        when(repo.findByYearAndStatusAndRangeStartLessThan(eq(2021), eq(FilingStatus.S), any()))
                .thenReturn(List.of(
                        tr
                ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.calculateTaxBreakdown(new TaxInput(2021, FilingStatus.S, "0")));
        assertTrue(ex.getMessage().toLowerCase().contains("income"));
    }

    @Test
    public void calculateTaxBreakdown_decimalIncome_parsesCorrectly() {

        TaxRate tr = new TaxRate(2021, FilingStatus.S, 0.10f, BigDecimal.ZERO, new BigDecimal("100000"));
        when(repo.findByYearAndStatusAndRangeStartLessThan(eq(2021), eq(FilingStatus.S), any()))
                .thenReturn(List.of(
                        tr
                ));

        TaxPaidResponse resp = service.calculateTaxBreakdown(new TaxInput(2021, FilingStatus.S, "12345.67"));
        assertTrue(resp.getTotalTaxPaid().contains("1,234"));
    }

    @Test
    public void taxInput_malformedIncome_throwsNumberFormat() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TaxInput(2021, FilingStatus.S, "12,34a5"));
        assertTrue(ex.getMessage().contains("Invalid income format"));
    }

    @Test
    public void getHistory_startAfterEnd_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getHistory(FilingStatus.S, Metric.TOP_RATE, 2021, 2020),
                "Invalid year range: 2021 - 2020");
    }

    @Test
    public void getHistory_nullMetric_throws() {
        when(repo.findByStatus(FilingStatus.S)).thenReturn(Collections.emptyList());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getHistory(FilingStatus.S, null, 1862, 2021));
        assertTrue(ex.getMessage().contains("Unsupported metric"));
    }

    @Test
    public void getHistory_noData_returnsEmpty() {
        when(repo.findByStatus(FilingStatus.MFJ)).thenReturn(Collections.emptyList());
        List<YearMetric> result = service.getHistory(FilingStatus.MFJ, Metric.BRACKET_COUNT, 1900, 1905);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void simulateBulk_emptyList_returnsEmpty() {
        List<TaxPaidResponse> out = service.simulateBulk(Collections.emptyList());
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    public void testSimulateBulk() {
        TaxService spySvc = Mockito.spy(service);
        TaxPaidResponse dummy = new TaxPaidResponse(List.of(), 1000f, 0.10f);
        doReturn(dummy).when(spySvc).calculateTaxBreakdown(any(TaxInput.class));

        List<TaxInput> inputs = List.of(
                new TaxInput(2021, FilingStatus.S, "50000"),
                new TaxInput(2021, FilingStatus.MFJ, "80000")
        );
        List<TaxPaidResponse> results = spySvc.simulateBulk(inputs);

        assertEquals(2, results.size());
        assertSame(dummy, results.get(0));
        assertSame(dummy, results.get(1));
    }

    @Test
    public void simulateBulk_mixedInputs_exceptionBubbles() {
        TaxInput good = new TaxInput(2021, FilingStatus.S, "1000");
        TaxInput bad  = new TaxInput(1800, FilingStatus.S, "5000"); // invalid year

        // Stub calculateTaxBreakdown to throw for the bad one
        TaxService spySvc = spy(service);
        doReturn(new TaxPaidResponse(List.of(), 100f, 0.1f))
                .when(spySvc).calculateTaxBreakdown(argThat(t -> t.getYear() == 2021));
        doThrow(new IllegalArgumentException("Invalid year: 1800"))
                .when(spySvc).calculateTaxBreakdown(argThat(t -> t.getYear() == 1800));

        assertThrows(IllegalArgumentException.class,
                () -> spySvc.simulateBulk(List.of(good, bad)));
    }

    @Test
    void simulateBulk_mixedInputs_bubblesException() {
        TaxInput good = new TaxInput(2021, FilingStatus.S, "10000");
        TaxInput bad  = new TaxInput(1800, FilingStatus.S, "1000");
        TaxService spy = spy(service);
        doReturn(new TaxPaidResponse(List.of(), 100f, 0.10f))
                .when(spy).calculateTaxBreakdown(argThat(i -> i.getYear() == 2021));
        doThrow(new IllegalArgumentException("Invalid year"))
                .when(spy).calculateTaxBreakdown(argThat(i -> i.getYear() == 1800));

        assertThrows(IllegalArgumentException.class,
                () -> spy.simulateBulk(List.of(good, bad)));
    }

}

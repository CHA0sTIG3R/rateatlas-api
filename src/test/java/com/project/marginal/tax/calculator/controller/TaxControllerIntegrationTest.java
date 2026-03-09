package com.project.marginal.tax.calculator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.marginal.tax.calculator.dto.*;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import com.project.marginal.tax.calculator.config.SecurityConfig;
import com.project.marginal.tax.calculator.exception.GlobalExceptionHandler;
import com.project.marginal.tax.calculator.security.ApiKeyFilter;
import com.project.marginal.tax.calculator.service.TaxDataImportService;
import com.project.marginal.tax.calculator.service.TaxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(controllers = TaxController.class)
@Import({GlobalExceptionHandler.class, ApiKeyFilter.class, SecurityConfig.class})
public class TaxControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaxService service;

    @MockitoBean
    private TaxDataImportService importService;

    @Value("${app.ingest.api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();

     @Test
     public void getYears_returnsOk() throws Exception {
         when(service.listYears()).thenReturn(List.of(2020, 2021));
         mockMvc.perform(get("/api/v1/tax/years"))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$", hasSize(2)));
     }

    @Test
    public void getFilingStatus_returnsOk() throws Exception {
        when(service.getFilingStatus()).thenReturn(Map.of("S", "Single", "MFJ", "Married Filing Jointly"));
        mockMvc.perform(get("/api/v1/tax/filing-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists())
                .andExpect(jsonPath("$", hasKey("S")));
    }

    @Test
    public void getRate_returnsOk() throws Exception{
        mockMvc.perform(get("/api/v1/tax/rate")
                        .param("year", "2021"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }

    @Test
    void getRate_invalidYear_returns400() throws Exception {
        when(service.getRates(eq(1800), any()))
                .thenThrow(new IllegalArgumentException("Invalid year: 1800"));
        mockMvc.perform(get("/api/v1/tax/rate").param("year","1800"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    public void breakdown_validRequest_returnsTaxPaidResponse() throws Exception {
        var dummyResponse = new com.project.marginal.tax.calculator.dto.TaxPaidResponse(List.of(), 5000f, 0.10f);
        when(service.calculateTaxBreakdown(any(TaxInput.class))).thenReturn(dummyResponse);

        TaxInput input = new TaxInput(2021, FilingStatus.S, "50000");
        mockMvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTaxPaid").exists());
    }

    @Test
    public void breakdown_negativeIncome_returns400() throws Exception {
        when(service.calculateTaxBreakdown(any(TaxInput.class)))
                .thenThrow(new IllegalArgumentException("Income must be non-negative"));

        TaxInput input = new TaxInput(2021, null, "-1000");
        String json = mapper.writeValueAsString(input);

        mockMvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", containsString("non-negative")))
                .andExpect(jsonPath("$.path", is("/api/v1/tax/breakdown")));
    }

    @Test
    public void breakdown_yearTooLow_returns400() throws Exception {
        when(service.calculateTaxBreakdown(any(TaxInput.class)))
                .thenThrow(new IllegalArgumentException("Invalid year: 1800"));

        TaxInput input = new TaxInput(1800, null, "50000");
        String json = mapper.writeValueAsString(input);

        mockMvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("Invalid year: 1800")));
    }

    @Test
    public void breakdown_malformedNumber_returns400InvalidNumber() throws Exception {
        when(service.calculateTaxBreakdown(any(TaxInput.class)))
                .thenThrow(new NumberFormatException("For input string: \"12,345\""));

        TaxInput input = new TaxInput(2021, null, "12,345");
        String json = mapper.writeValueAsString(input);

        mockMvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid Number")))
                .andExpect(jsonPath("$.message", containsString("12,345")));
    }

    @Test
    public void breakdown_decimalIncome_returns200() throws Exception {
        TaxPaidResponse dummy = new TaxPaidResponse(List.of(), 1234.56f, 0.10f);
        when(service.calculateTaxBreakdown(any(TaxInput.class)))
                .thenReturn(dummy);

        TaxInput input = new TaxInput(2021, null, "12345.67");
        String json = mapper.writeValueAsString(input);

        mockMvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTaxPaid", containsString("1,234")))
                .andExpect(jsonPath("$.avgRate", containsString("%")));
    }

    @Test
    public void getSummary_valid_returnsSummary() throws Exception {
        var summary = new TaxSummaryResponse(2022, FilingStatus.S, 3,
                BigDecimal.ZERO, new BigDecimal("20000"), "12%", null);
        when(service.getSummary(2022, FilingStatus.S)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/tax/summary")
                        .param("year","2022")
                        .param("status","S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year", is(2022)))
                .andExpect(jsonPath("$.averageRate", is("12%")));
    }

    @Test
    void getSummary_invalidYear_returns400() throws Exception {
        when(service.getSummary(1700, FilingStatus.S))
                .thenThrow(new IllegalArgumentException("Invalid year: 1700"));
        mockMvc.perform(get("/api/v1/tax/summary")
                        .param("year","1700")
                        .param("status","S"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    public void history_missingStatusParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/tax/history")
                        .param("metric", "TOP_RATE")
                        .param("startYear", "2000")
                        .param("endYear", "2020"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Missing Parameter")))
                .andExpect(jsonPath("$.message", containsString("status parameter is required")));
    }

    @Test
    public void history_invalidStartYear_returns400TypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/tax/history")
                        .param("status", "S")
                        .param("metric", "TOP_RATE")
                        .param("startYear", "notAnInt")
                        .param("endYear", "2020"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Type Mismatch")))
                .andExpect(jsonPath("$.message", containsString("Parameter 'startYear' must be 'Integer'")));
    }

    @Test
    public void getHistory_validRequest_returnsMetrics() throws Exception {
        YearMetric ym = new YearMetric(2020, Metric.BRACKET_COUNT, "3");
        when(service.getHistory(
                eq(FilingStatus.S),
                eq(Metric.BRACKET_COUNT),
                eq(2019),
                eq(2021)))
                .thenReturn(List.of(ym));

        mockMvc.perform(get("/api/v1/tax/history")
                        .param("status", "S")
                        .param("metric", "BRACKET_COUNT")
                        .param("startYear", "2019")
                        .param("endYear", "2021"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].year", is(2020)))
                .andExpect(jsonPath("$[0].metric", is("BRACKET_COUNT")))
                .andExpect(jsonPath("$[0].value", is("3")));
    }

    @Test
    public void history_startYearAfterEndYear_returns400() throws Exception {
        when(service.getHistory(eq(FilingStatus.S), eq(Metric.TOP_RATE), eq(2021), eq(2000)))
                .thenThrow(new IllegalArgumentException("startYear must be ≤ endYear"));

        mockMvc.perform(get("/api/v1/tax/history")
                        .param("status", "S")
                        .param("metric", "TOP_RATE")
                        .param("startYear", "2021")
                        .param("endYear", "2000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("startYear must be ≤ endYear")));
    }

    @Test
    public void history_unsupportedMetric_returns400TypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/tax/history")
                        .param("status", "S")
                        .param("metric", "MAX_RATE")   // not in Metric enum
                        .param("startYear", "2000")
                        .param("endYear", "2020"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Type Mismatch")))
                .andExpect(jsonPath("$.message", containsString("Parameter 'metric' must be 'Metric'")));
    }

    @Test
    public void history_noData_returnsEmptyArray() throws Exception {
        when(service.getHistory(any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/tax/history")
                        .param("status", "S")
                        .param("metric", "BRACKET_COUNT")
                        .param("startYear", "2000")
                        .param("endYear", "2005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    public void simulate_emptyList_returnsEmptyArray() throws Exception {
        when(service.simulateBulk(anyList()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(post("/api/v1/tax/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    public void simulate_bulkInputs_returnsListOfResponses() throws Exception {
        TaxPaidResponse resp1 = new TaxPaidResponse(List.of(), 500f, 0.05f);
        TaxPaidResponse resp2 = new TaxPaidResponse(List.of(), 800f, 0.08f);
        when(service.simulateBulk(anyList()))
                .thenReturn(List.of(resp1, resp2));

        List<TaxInput> inputs = List.of(
                new TaxInput(2021, FilingStatus.S, "5000"),
                new TaxInput(2021, FilingStatus.MFJ, "10000")
        );
        String json = mapper.writeValueAsString(inputs);

        mockMvc.perform(post("/api/v1/tax/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].totalTaxPaid", containsString("$")))
                .andExpect(jsonPath("$[1].totalTaxPaid", containsString("$")));
    }

    @Test
    void simulateBulk_performance() {
        List<TaxInput> inputs = IntStream.range(0,500)
                .mapToObj(i -> new TaxInput(2021, FilingStatus.S, "100000"))
                .toList();
        when(service.simulateBulk(anyList()))
                .thenReturn(inputs.stream()
                        .map(input -> new TaxPaidResponse(List.of(), 1000f, 0.10f))
                        .toList());
        long start = System.nanoTime();
        List<TaxPaidResponse> results = service.simulateBulk(inputs);
        long elapsedMs = (System.nanoTime() - start)/1_000_000;
        assertEquals(500, results.size());
        assertTrue(elapsedMs < 2000, "simulateBulk too slow: " + elapsedMs + "ms");
    }

    @Test
    public void uploadWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/tax/upload")
                        .contentType("text/csv")
                        .content("dummy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void uploadWithKeyIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/tax/upload")
                        .header("X-API-Key", apiKey)
                        .contentType("text/csv")
                        .content("dummy"))
                .andExpect(status().isOk());
    }

}

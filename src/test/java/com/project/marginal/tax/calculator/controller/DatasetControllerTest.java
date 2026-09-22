package com.project.marginal.tax.calculator.controller;

import com.project.marginal.tax.calculator.config.SecurityConfig;
import com.project.marginal.tax.calculator.dto.DatasetFreshnessResponse;
import com.project.marginal.tax.calculator.exception.GlobalExceptionHandler;
import com.project.marginal.tax.calculator.filter.ApiKeyFilter;
import com.project.marginal.tax.calculator.filter.RateLimitFilter;
import com.project.marginal.tax.calculator.service.DatasetService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DatasetController.class)
@Import({GlobalExceptionHandler.class, ApiKeyFilter.class, SecurityConfig.class})
public class DatasetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatasetService datasetService;

    @MockitoBean
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUpRateLimitFilter() throws Exception {
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(rateLimitFilter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    void getLatestDataset_fresh_returnsOkWithBothFreshnessFields() throws Exception {
        when(datasetService.getLatestDataset()).thenReturn(DatasetFreshnessResponse.builder()
                .latestAvailableTaxYear(2024)
                .irsPageLastUpdated(LocalDate.of(2024, 1, 15))
                .lastIngestedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .freshnessState("FRESH")
                .ingestSignalFreshnessState("FRESH")
                .sourceUrl("https://www.irs.gov/filing/federal-income-tax-rates-and-brackets")
                .build());

        mockMvc.perform(get("/api/v1/datasets/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessState").value("FRESH"))
                .andExpect(jsonPath("$.ingestSignalFreshnessState").value("FRESH"))
                .andExpect(jsonPath("$.latestAvailableTaxYear").value(2024));
    }

    @Test
    void getLatestDataset_stale_returnsOkWithStaleState() throws Exception {
        when(datasetService.getLatestDataset()).thenReturn(DatasetFreshnessResponse.builder()
                .latestAvailableTaxYear(2018)
                .irsPageLastUpdated(LocalDate.of(2018, 1, 15))
                .lastIngestedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(500))
                .freshnessState("STALE")
                .ingestSignalFreshnessState("STALE")
                .sourceUrl("https://www.irs.gov/filing/federal-income-tax-rates-and-brackets")
                .build());

        mockMvc.perform(get("/api/v1/datasets/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessState").value("STALE"))
                .andExpect(jsonPath("$.ingestSignalFreshnessState").value("STALE"));
    }

    @Test
    void getLatestDataset_missingMetadata_returns503() throws Exception {
        when(datasetService.getLatestDataset())
                .thenThrow(new IllegalStateException("No ingest metadata found"));

        mockMvc.perform(get("/api/v1/datasets/latest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("No ingest metadata found"));
    }
}

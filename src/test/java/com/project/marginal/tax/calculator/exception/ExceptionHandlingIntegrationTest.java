package com.project.marginal.tax.calculator.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.marginal.tax.calculator.controller.TaxController;
import com.project.marginal.tax.calculator.dto.TaxInput;
import com.project.marginal.tax.calculator.security.ApiKeyFilter;
import com.project.marginal.tax.calculator.service.CacheService;
import com.project.marginal.tax.calculator.service.TaxDataImportService;
import com.project.marginal.tax.calculator.service.TaxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaxController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false) // Disable default filters to use custom ApiKeyFilter
public class ExceptionHandlingIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TaxService taxService;
    @MockitoBean
    private TaxDataImportService importService;

    @MockitoBean
    private ApiKeyFilter apiKeyFilter; // Mock the ApiKeyFilter to avoid actual API key checks

    @MockitoBean
    private CacheService cacheService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void whenMissingParam_then400AndJsonError() throws Exception {
        mvc.perform(get("/api/v1/tax/rate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Missing Parameter")))
                .andExpect(jsonPath("$.message", containsString("year parameter is required")))
                .andExpect(jsonPath("$.path", is("/api/v1/tax/rate")));
    }

    @Test
    public void whenTypeMismatch_then400AndJsonError() throws Exception {
        mvc.perform(get("/api/v1/tax/rate").param("year", "notAnInt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Type Mismatch")))
                .andExpect(jsonPath("$.message", containsString("Parameter 'year' must be 'int'")));
    }

    @Test
    public void whenMalformedJsonOnPost_then400AndJsonError() throws Exception {
        String badJson = "{ year: 2021, income: }";
        mvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Malformed JSON")))
                .andExpect(jsonPath("$.message", containsString("Unexpected")));
    }

    @Test
    public void whenServiceThrowsInvalidYear_thenBadRequest() throws Exception {
        when(taxService.calculateTaxBreakdown(any(TaxInput.class)))
                .thenThrow(new IllegalArgumentException("Invalid year: 1800"));

        String body = mapper.writeValueAsString(new TaxInput(1800, null, "1000"));
        mvc.perform(post("/api/v1/tax/breakdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("Invalid year: 1800")));
    }
}
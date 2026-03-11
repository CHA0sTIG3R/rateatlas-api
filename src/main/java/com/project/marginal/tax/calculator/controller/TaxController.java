/*
 * Copyright 2025 Hamzat Olowu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * GitHub: https//github.com/CHA0sTIG3R
 */

package com.project.marginal.tax.calculator.controller;

import com.project.marginal.tax.calculator.dto.*;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import com.project.marginal.tax.calculator.service.CacheService;
import com.project.marginal.tax.calculator.service.TaxDataImportService;
import com.project.marginal.tax.calculator.service.TaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/tax")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService service;
    private final TaxDataImportService importService;
    private final CacheService cacheService;

    @PostMapping(
            path = "/upload",
            consumes = "text/csv",
            produces = "application/json"
    )
    public ResponseEntity<String> updateTaxRates(@RequestBody byte[] csvData) {
        try (InputStream in = new ByteArrayInputStream(csvData)) {
            importService.importData(in);
            cacheService.evictByPattern("brackets:*");
            cacheService.evictByPattern("calc:*");
            return ResponseEntity.ok("Tax rates updated successfully.");
        }
        catch (Exception e) {
            System.err.println("Failed to update tax rates: " + e.getMessage());
            return ResponseEntity
                    .badRequest()
                    .body("Failed to update tax rates: " + e.getMessage());
        }
    }


    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getYears() {
        return ResponseEntity.ok(service.listYears());
    }

    @GetMapping("/filing-status")
    public ResponseEntity<Map<String, String>> getFilingStatus() {
        return ResponseEntity.ok(service.getFilingStatus());
    }

    @GetMapping("/rate")
    public ResponseEntity<List<TaxRateDto>> getRate(@RequestParam int year,
                                    @RequestParam(required = false) FilingStatus status) throws IllegalArgumentException {
        return ResponseEntity.ok(service.getRates(year, status));
    }

    @PostMapping("/breakdown")
    public ResponseEntity<TaxPaidResponse> getTaxBreakdown(@RequestBody TaxInput taxInput) throws IllegalArgumentException {
        return ResponseEntity.ok(service.calculateTaxBreakdown(taxInput));
    }

    @GetMapping("/summary")
    public ResponseEntity<TaxSummaryResponse> getSummary(@RequestParam int year, @RequestParam FilingStatus status) throws IllegalArgumentException {
        return ResponseEntity.ok(service.getSummary(year, status));
    }

    @GetMapping("/history")
    public ResponseEntity<List<YearMetric>> getHistory(@RequestParam FilingStatus status,
                                       @RequestParam(defaultValue = "TOP_RATE") Metric metric,
                                       @RequestParam(defaultValue = "1862") Integer startYear,
                                       @RequestParam(defaultValue = "2021") Integer endYear) throws IllegalArgumentException {
        return ResponseEntity.ok(service.getHistory(status, metric, startYear, endYear));
    }

    @PostMapping("/simulate")
    public ResponseEntity<List<TaxPaidResponse>> simulate(@RequestBody List<TaxInput> taxInputs) throws IllegalArgumentException {
        return ResponseEntity.ok(service.simulateBulk(taxInputs));
    }
}

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

package com.project.marginal.tax.calculator.service;

import com.project.marginal.tax.calculator.dto.*;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import com.project.marginal.tax.calculator.entity.NoIncomeTaxYear;
import com.project.marginal.tax.calculator.entity.TaxRate;
import com.project.marginal.tax.calculator.metrics.MetricsService;
import com.project.marginal.tax.calculator.repository.NoIncomeTaxYearRepository;
import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.project.marginal.tax.calculator.utility.NumberFormatUtils.percentFormat;

@Service
@RequiredArgsConstructor
public class TaxService {

    private static final Logger log = LoggerFactory.getLogger(TaxService.class);

    final int MIN_YEAR = 1862;
    final int MAX_YEAR = Year.now().getValue() - 1;

    private final TaxRateRepository taxRateRepo;
    private final NoIncomeTaxYearRepository noTaxRepo;
    private final MetricsService metricsService;
    private final CacheService cacheService;

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isNotValidYear(int year) {
        return year < MIN_YEAR || year > MAX_YEAR;
    }

    @SuppressWarnings("unchecked")
    private boolean isNoTaxYear(int year) {
        Set<Integer> noTaxYears = (Set<Integer>) cacheService.get("noTaxYears:all");
        if (noTaxYears == null) {
            noTaxYears = noTaxRepo.findAll().stream()
                    .map(NoIncomeTaxYear::getYear)
                    .collect(Collectors.toUnmodifiableSet());
            cacheService.put("noTaxYears:all", noTaxYears);
        }
        return noTaxYears.contains(year);
    }

    private void validateTaxInput(TaxInput taxInput) {
        if (isNotValidYear(taxInput.getYear())) {
            throw new IllegalArgumentException("Invalid year: " + taxInput.getYear());
        }
        if (taxInput.getIncome() <= 0) {
            throw new IllegalArgumentException("Income must be greater than 0");
        }
        if (taxInput.getStatus() == null) {
            throw new IllegalArgumentException("Filing status must be provided");
        }
    }

    private TaxRateDto mapToTaxRateDto(TaxRate taxRate) {
        return new TaxRateDto(
                taxRate.getYear(),
                taxRate.getStatus(),
                taxRate.getRangeStart().floatValue(),
                taxRate.getRangeEnd() != null ? taxRate.getRangeEnd().floatValue() : 0,
                taxRate.getRate()
        );
    }

    // Cache key builders — single source of truth for each key pattern
    private String bracketsKey(int year, FilingStatus status) {
        return String.format("brackets:%d:%s", year, status != null ? status.name() : "ALL");
    }

    private String calcKey(TaxInput taxInput) {
        return String.format("calc:%d:%s:%f", taxInput.getYear(), taxInput.getStatus().name(), taxInput.getIncome());
    }

    private String summaryKey(int year, FilingStatus status) {
        return String.format("summary:%d:%s", year, status != null ? status.name() : "ALL");
    }

    private String historyKey(FilingStatus status, Metric metric, Integer startYear, Integer endYear) {
        return String.format("history:%s:%s:%d:%d", status, metric, startYear, endYear);
    }

    // Generic fallback helper — checks cache then throws 503
    @SuppressWarnings("unchecked")
    private <T> T getFromCacheOrThrow(String cacheKey, String errorMessage) {
        T cached = (T) cacheService.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        throw new IllegalStateException(errorMessage);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @CircuitBreaker(name = "database", fallbackMethod = "listYearsFallback")
    public List<Integer> listYears() {
        List<Integer> noTaxYears = noTaxRepo.findAll().stream()
                .map(NoIncomeTaxYear::getYear)
                .toList();

        List<Integer> bracketYears = taxRateRepo.findAll().stream()
                .map(TaxRate::getYear)
                .toList();

        List<Integer> result = Stream.concat(bracketYears.stream(), noTaxYears.stream())
                .distinct()
                .sorted()
                .toList();

        cacheService.put("years:all", result);
        return result;
    }

    public Map<String, String> getFilingStatus() {
        return FilingStatus.toMap();
    }

    public List<TaxRateDto> getTaxRateByYear(int year) {
        return taxRateRepo.findByYear(year).stream()
                .map(this::mapToTaxRateDto)
                .toList();
    }

    public List<TaxRateDto> getTaxRateByYearAndStatus(int year, FilingStatus status) {
        return taxRateRepo.findByYearAndStatus(year, status).stream()
                .map(this::mapToTaxRateDto)
                .toList();
    }

    @WithSpan("tax.brackets.lookup")
    @CircuitBreaker(name = "database", fallbackMethod = "getRatesFallback")
    public List<TaxRateDto> getRates(@SpanAttribute("tax.year") int year, @SpanAttribute("tax.status") FilingStatus status) {
        if (isNotValidYear(year)) {
            throw new IllegalArgumentException("Invalid year: " + year);
        }

        if (isNoTaxYear(year)) {
            String msg = noTaxRepo.findById(year)
                    .map(NoIncomeTaxYear::getMessage)
                    .orElse("No income tax for year " + year);
            return List.of(TaxRateDto.noIncomeTax(year, status, msg));
        }

        String cacheKey = bracketsKey(year, status);
        @SuppressWarnings("unchecked")
        List<TaxRateDto> cached = (List<TaxRateDto>) cacheService.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<TaxRateDto> result = status == null
                ? getTaxRateByYear(year)
                : getTaxRateByYearAndStatus(year, status);

        cacheService.put(cacheKey, result);
        return result;
    }

    public List<TaxRate> getTaxRateByYearAndStatusAndRangeStartLessThan(int year, FilingStatus status, float income) {
        return taxRateRepo.findByYearAndStatusAndRangeStartLessThan(year, status, new BigDecimal(income));
    }

    public List<Float> calculateTax(TaxInput taxInput) {
        List<TaxRate> taxRates = getTaxRateByYearAndStatusAndRangeStartLessThan(
                taxInput.getYear(),
                taxInput.getStatus(),
                taxInput.getIncome()
        );
        var taxPaidPerBracket = new ArrayList<Float>();
        float income = taxInput.getIncome();

        for (TaxRate taxRate : taxRates) {
            float taxPaid;
            if (income > taxRate.getRangeStart().floatValue()) {
                if (taxRate.getRangeEnd() == null) {
                    taxPaid = (income - taxRate.getRangeStart().floatValue()) * (taxRate.getRate());
                } else {
                    float rangeEnd = Math.min(income, taxRate.getRangeEnd().floatValue());
                    taxPaid = (rangeEnd - taxRate.getRangeStart().floatValue()) * (taxRate.getRate());
                }
                taxPaidPerBracket.add(taxPaid);
            }
        }

        return taxPaidPerBracket;
    }

    public List<TaxPaidInfo> getTaxPaidInfo(TaxInput taxInput) {
        List<TaxRate> taxRates = getTaxRateByYearAndStatusAndRangeStartLessThan(
                taxInput.getYear(),
                taxInput.getStatus(),
                taxInput.getIncome()
        );
        var taxPaidPerBracket = calculateTax(taxInput);
        var taxPaidInfos = new ArrayList<TaxPaidInfo>();
        float income = taxInput.getIncome();

        if (taxRates.isEmpty()) {
            throw new IllegalArgumentException("No tax rates found for the given year and status");
        }

        for (int i = 0; i < taxRates.size(); i++) {
            TaxRate taxRate = taxRates.get(i);
            float rangeStart = taxRate.getRangeStart().floatValue();
            float rangeEnd = taxRate.getRangeEnd() != null ? Math.min(income, taxRate.getRangeEnd().floatValue()) : income;
            float taxPaid = taxPaidPerBracket.get(i);

            TaxPaidInfo info = new TaxPaidInfo(taxInput.getYear(), taxInput.getStatus(), rangeStart, rangeEnd, taxRate.getRate(), taxPaid);
            taxPaidInfos.add(info);
        }

        return taxPaidInfos;
    }

    public float getTotalTaxPaid(TaxInput taxInput) {
        return (float) calculateTax(taxInput).stream()
                .mapToDouble(Float::floatValue)
                .sum();
    }

    @WithSpan("tax.calculate.breakdown")
    @CircuitBreaker(name = "database", fallbackMethod = "calculateTaxBreakdownFallback")
    public TaxPaidResponse calculateTaxBreakdown(@SpanAttribute("tax.year") TaxInput taxInput) throws IllegalArgumentException {
        validateTaxInput(taxInput);

        if (isNoTaxYear(taxInput.getYear())) {
            String msg = noTaxRepo.findById(taxInput.getYear())
                    .map(NoIncomeTaxYear::getMessage)
                    .orElse("No income tax for year " + taxInput.getYear());
            return TaxPaidResponse.noIncomeTax(msg);
        }

        String cacheKey = calcKey(taxInput);
        TaxPaidResponse cached = (TaxPaidResponse) cacheService.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<TaxPaidInfo> taxPaidInfos = getTaxPaidInfo(taxInput);
        float totalTaxPaid = getTotalTaxPaid(taxInput);
        float avgRate = totalTaxPaid / taxInput.getIncome();

        TaxPaidResponse result = new TaxPaidResponse(taxPaidInfos, totalTaxPaid, avgRate);
        cacheService.put(cacheKey, result);

        metricsService.recordTaxCalculation();
        return result;
    }

    @WithSpan("tax.summary")
    @CircuitBreaker(name = "database", fallbackMethod = "getSummaryFallback")
    public TaxSummaryResponse getSummary(@SpanAttribute("tax.year") int year, @SpanAttribute("tax.status") FilingStatus status) throws IllegalArgumentException {
        if (isNotValidYear(year)) {
            throw new IllegalArgumentException("Invalid year: " + year);
        }

        if (isNoTaxYear(year)) {
            String msg = noTaxRepo.findById(year).map(NoIncomeTaxYear::getMessage)
                    .orElse("No income tax for year " + year);
            return TaxSummaryResponse.noIncomeTax(year, status, msg);
        }

        List<TaxRate> taxRates = taxRateRepo.findByYearAndStatus(year, status);
        int bracketCount = taxRates.size();

        BigDecimal minThreshold = taxRates.stream()
                .map(TaxRate::getRangeStart)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal maxThreshold = taxRates.stream()
                .map(TaxRate::getRangeEnd)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        double avgRateRaw = taxRates.stream()
                .mapToDouble(TaxRate::getRate)
                .average()
                .orElse(0.0);

        String averageRate = avgRateRaw == 0.0 ? "No Income Tax" : percentFormat(avgRateRaw);

        TaxSummaryResponse result = TaxSummaryResponse.normal(year, status, bracketCount, minThreshold, maxThreshold, averageRate);
        cacheService.put(summaryKey(year, status), result);
        return result;
    }

    @WithSpan("tax.history")
    @CircuitBreaker(name = "database", fallbackMethod = "getHistoryFallback")
    public List<YearMetric> getHistory(
            @SpanAttribute("tax.status") FilingStatus status,
            @SpanAttribute("tax.metric") Metric metric,
            @SpanAttribute("tax.start_year") Integer startYear,
            @SpanAttribute("tax.end_year") Integer endYear
    ) {
        if ((isNotValidYear(startYear) || isNotValidYear(endYear)) || (startYear > endYear)) {
            throw new IllegalArgumentException("Invalid year range: " + startYear + " - " + endYear);
        }

        if (metric == null) {
            throw new IllegalArgumentException("Unsupported metric: " + null);
        }

        List<Integer> bracketYears = taxRateRepo.findByStatus(status).stream()
                .map(TaxRate::getYear)
                .distinct()
                .sorted()
                .filter(year -> year >= startYear && year <= endYear)
                .toList();

        List<Integer> noTaxYears = noTaxRepo.findAll().stream()
                .map(NoIncomeTaxYear::getYear)
                .filter(year -> year >= startYear && year <= endYear)
                .toList();

        List<Integer> years = Stream.concat(bracketYears.stream(), noTaxYears.stream())
                .distinct()
                .sorted()
                .toList();

        List<YearMetric> result = years.stream().map(y -> {
            List<TaxRate> rates = taxRateRepo.findByYearAndStatus(y, status);
            if (noTaxYears.contains(y)) {
                String msg = noTaxRepo.findById(y)
                        .map(NoIncomeTaxYear::getMessage)
                        .orElse("No income tax for year " + y);
                return YearMetric.noIncomeTax(y, metric, msg);
            }
            String val;
            switch (metric) {
                case TOP_RATE -> {
                    double maxRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .max()
                            .orElse(0.0);
                    val = maxRate == 0d ? "No Income Tax" : percentFormat(maxRate);
                }

                case MIN_RATE -> {
                    double minRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .min()
                            .orElse(0.0);
                    val = minRate == 0d ? "No Income Tax" : percentFormat(minRate);
                }

                case AVERAGE_RATE -> {
                    double avgRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .average()
                            .orElse(0.0);
                    val = avgRate == 0d ? "No Income Tax" : percentFormat(avgRate);
                }

                case BRACKET_COUNT -> {
                    int bracketCount = rates.size();
                    val = String.valueOf(bracketCount);
                }

                default -> throw new IllegalArgumentException("Unsupported metric: " + metric);
            }
            return new YearMetric(y, metric, val);
        }).toList();

        cacheService.put(historyKey(status, metric, startYear, endYear), result);
        return result;
    }

    @WithSpan("tax.simulate.bulk")
    @CircuitBreaker(name = "database", fallbackMethod = "simulateBulkFallback")
    public List<TaxPaidResponse> simulateBulk(List<TaxInput> taxInputs) {
        metricsService.recordSimulateBulk();
        return taxInputs.stream()
                .map(this::calculateTaxBreakdown)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Fallback methods — invoked by Resilience4j via reflection
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    private List<Integer> listYearsFallback(Throwable t) {
        log.warn("listYears fallback triggered: {}", t.getMessage());
        return getFromCacheOrThrow("years:all", "Tax year list is temporarily unavailable. Please try again later.");
    }

    @SuppressWarnings("unused")
    private List<TaxRateDto> getRatesFallback(int year, FilingStatus status, Throwable t) {
        log.warn("getRates fallback triggered for year={} status={}: {}", year, status, t.getMessage());
        return getFromCacheOrThrow(bracketsKey(year, status), "Tax rate data is temporarily unavailable. Please try again later.");
    }

    @SuppressWarnings("unused")
    private TaxPaidResponse calculateTaxBreakdownFallback(TaxInput taxInput, Throwable t) {
        log.warn("calculateTaxBreakdown fallback triggered for year={} status={}: {}", taxInput.getYear(), taxInput.getStatus(), t.getMessage());
        return getFromCacheOrThrow(calcKey(taxInput), "Tax calculation is temporarily unavailable. Please try again later.");
    }

    @SuppressWarnings("unused")
    private TaxSummaryResponse getSummaryFallback(int year, FilingStatus status, Throwable t) {
        log.warn("getSummary fallback triggered for year={} status={}: {}", year, status, t.getMessage());
        return getFromCacheOrThrow(summaryKey(year, status), "Tax summary is temporarily unavailable. Please try again later.");
    }

    @SuppressWarnings("unused")
    private List<YearMetric> getHistoryFallback(FilingStatus status, Metric metric, Integer startYear, Integer endYear, Throwable t) {
        log.warn("getHistory fallback triggered for status={} metric={} range={}-{}: {}", status, metric, startYear, endYear, t.getMessage());
        return getFromCacheOrThrow(historyKey(status, metric, startYear, endYear), "Tax history is temporarily unavailable. Please try again later.");
    }

    @SuppressWarnings("unused")
    private List<TaxPaidResponse> simulateBulkFallback(List<TaxInput> taxInputs, Throwable t) {
        log.warn("simulateBulk fallback triggered for {} inputs: {}", taxInputs.size(), t.getMessage());
        throw new IllegalStateException("Bulk simulation is temporarily unavailable. Please try again later.");
    }
}

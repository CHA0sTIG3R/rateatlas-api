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
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Year;
import java.util.*;
import java.util.stream.Stream;

import static com.project.marginal.tax.calculator.utility.NumberFormatUtils.percentFormat;

@Service
@RequiredArgsConstructor
public class TaxService {

    final int MIN_YEAR = 1862;
    final int MAX_YEAR = Year.now().getValue() - 1;

    private final TaxRateRepository taxRateRepo;
    private final NoIncomeTaxYearRepository noTaxRepo;
    private final MetricsService metricsService;
    private final CacheService cacheService;

    private boolean isNotValidYear(int year) {
        return year < MIN_YEAR || year > MAX_YEAR;
    }
    private boolean isNoTaxYear(int year) {
        return noTaxRepo.existsById(year);
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

    public List<Integer> listYears() {
        List <Integer> noTaxYears = noTaxRepo.findAll().stream()
                .map(NoIncomeTaxYear::getYear)
                .toList();

        List<Integer> bracketYears =  taxRateRepo.findAll().stream()
                .map(TaxRate::getYear)
                .toList();

        return Stream.concat(bracketYears.stream(), noTaxYears.stream())
                .distinct()
                .sorted()
                .toList();
    }

    public Map<String, String> getFilingStatus() {
        return FilingStatus.toMap();
    }

    public List<TaxRateDto> getTaxRateByYear(int year) {
        return taxRateRepo.findByYear(year).stream()
                .map(taxRate -> new TaxRateDto(
                        taxRate.getYear(),
                        taxRate.getStatus(),
                        taxRate.getRangeStart().floatValue(),
                        taxRate.getRangeEnd() != null ? taxRate.getRangeEnd().floatValue(): 0,
                        taxRate.getRate()
                ))
                .toList();
    }

    public List<TaxRateDto> getTaxRateByYearAndStatus(int year, FilingStatus status) {
        return taxRateRepo.findByYearAndStatus(year, status).stream()
                .map(taxRate -> new TaxRateDto(
                        taxRate.getYear(),
                        taxRate.getStatus(),
                        taxRate.getRangeStart().floatValue(),
                        taxRate.getRangeEnd() != null ? taxRate.getRangeEnd().floatValue(): 0,
                        taxRate.getRate()
                ))
                .toList();
    }

    @WithSpan("tax.brackets.lookup")
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

        String cacheKey = String.format("brackets:%d:%s", year, status != null? status.name() : "ALL");
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
    public TaxPaidResponse calculateTaxBreakdown(@SpanAttribute("tax.year") TaxInput taxInput) throws IllegalArgumentException {
        validateTaxInput(taxInput);

        if (isNoTaxYear(taxInput.getYear())) {
            String msg = noTaxRepo.findById(taxInput.getYear())
                    .map(NoIncomeTaxYear::getMessage)
                    .orElse("No income tax for year " + taxInput.getYear());
            return TaxPaidResponse.noIncomeTax(msg);
        }

        String cacheKey = String.format("calc:%d:%s:%f", taxInput.getYear(), taxInput.getStatus().name(), taxInput.getIncome());
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

        return TaxSummaryResponse.normal(year, status, bracketCount, minThreshold, maxThreshold, averageRate);
    }

    @WithSpan("tax.history")
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

        return years.stream().map(y -> {
            List<TaxRate> rates = taxRateRepo.findByYearAndStatus(y, status);
            if (noTaxYears.contains(y)) {
                String msg = noTaxRepo.findById(y)
                        .map(NoIncomeTaxYear::getMessage)
                        .orElse("No income tax for year " + y);
                return  YearMetric.noIncomeTax(y, metric, msg);
            }
            String val;
            switch (metric) {
                case TOP_RATE -> {
                    double maxRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .max()
                            .orElse(0.0);
                    val = maxRate==0d? "No Income Tax" : percentFormat(maxRate);
                }

                case MIN_RATE -> {
                    double minRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .min()
                            .orElse(0.0);
                    val = minRate==0d? "No Income Tax" :  percentFormat(minRate);
                }

                case AVERAGE_RATE -> {
                    double avgRate = rates.stream()
                            .mapToDouble(TaxRate::getRate)
                            .average()
                            .orElse(0.0);
                    val = avgRate==0d? "No Income Tax" :  percentFormat(avgRate);
                }

                case BRACKET_COUNT -> {
                    int bracketCount = rates.size();
                    val = String.valueOf(bracketCount);
                }

            default -> throw new IllegalArgumentException("Unsupported metric: " + metric);
        }
        return new YearMetric(y, metric, val);
        }).toList();
    }

    @WithSpan("tax.simulate.bulk")
    public List<TaxPaidResponse> simulateBulk(List<TaxInput> taxInputs) {
        metricsService.recordSimulateBulk();
        return taxInputs.stream()
                .map(this::calculateTaxBreakdown)
                .toList();
    }
}

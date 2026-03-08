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

package com.project.marginal.tax.calculator.utility;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.project.marginal.tax.calculator.dto.BracketEntry;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.*;

@Component
public class CsvImportUtils {

    /**
     * This method is used to import tax rates from a CSV file.
     * It reads the CSV file, parses the data, and populates the rate list with BracketEntry objects.
     *
     * @param in The path to the CSV file.
     * @return A list of BracketEntry objects representing the tax rates.
     * @throws IOException If an I/O error occurs while reading the file.
     * @throws CsvValidationException If a CSV validation error occurs.
     */
    public List<BracketEntry> importFromStream(InputStream in) throws IOException, CsvValidationException {
        List<BracketEntry> rates = new ArrayList<>();

        try (Reader reader = new InputStreamReader(in)){
            CSVReader csvReader = new CSVReader(reader);

            csvReader.readNext();

            String[] line;
            while ((line = csvReader.readNext()) != null){
                if (Objects.equals(line[0], "")){
                    continue;
                }

                Integer year = Integer.valueOf(line[0]);

                if (!line[1].isEmpty()) {
                    insertTaxRate(rates, year, FilingStatus.MFJ, line[1], line[2], line[3]);
                }
                if (!(line[4].isEmpty() && line[1].isEmpty())) {
                    insertTaxRate(rates, year, FilingStatus.MFS, line[4], line[5], line[6]);
                }
                if (!(line[7].isEmpty() && line[1].isEmpty())) {
                    insertTaxRate(rates, year, FilingStatus.S, line[7], line[8], line[9]);
                }
                if (!(line[10].isEmpty() && line[1].isEmpty())) {
                    insertTaxRate(rates, year, FilingStatus.HOH, line[10], line[11], line[12]);
                }
            }
        }

        return rates;
    }

    /**
     * This method is used to insert a tax rate into the rate list.
     * It creates a new BracketEntry object and sets its properties based on the provided parameters.
     *
     * @param rates The list of tax rates to add to.
     * @param year The year of the tax rate.
     * @param status The filing status (e.g., "Married Filing Jointly").
     * @param rawRate The raw tax rate as a string (e.g., "24%").
     * @param rawStart The raw starting range as a string (e.g., "$50,000").
     */
    private void insertTaxRate( List<BracketEntry> rates, Integer year, FilingStatus status, String rawRate, String rawStart, String rawEnd) {

        boolean isNoIncomeTax = rawRate.isEmpty() || rawRate.equalsIgnoreCase("No income tax");

        Float rate = isNoIncomeTax ? 0f : Float.parseFloat(rawRate.replace("%", "").trim()) / 100;

        BigDecimal start = isNoIncomeTax ? BigDecimal.ZERO : parseDollarValue(rawStart);
        BigDecimal end = isNoIncomeTax ? null : parseDollarValue(rawEnd);

        BracketEntry bracketEntry = new BracketEntry();
        bracketEntry.setYear(year);
        bracketEntry.setStatus(status);
        bracketEntry.setRate(rate);
        bracketEntry.setRangeStart(start);
        bracketEntry.setRangeEnd(end);
        rates.add(bracketEntry);
    }

    /**
     * This method is used to parse the dollar value from a string.
     * It removes the dollar sign and commas, and converts it to a BigDecimal.
     *
     * @param dollarStr The dollar strings to be parsed.
     * @return The parsed BigDecimal value.
     */
    private BigDecimal parseDollarValue(String dollarStr) {
        String cleaned = dollarStr.replace("$", "").replace(",", "");
        if (cleaned.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(cleaned.trim());
    }

}

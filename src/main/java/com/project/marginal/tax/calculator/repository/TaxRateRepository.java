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

package com.project.marginal.tax.calculator.repository;

import com.project.marginal.tax.calculator.entity.FilingStatus;
import com.project.marginal.tax.calculator.entity.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing TaxRate entities.
 * <p>
 * This interface extends JpaRepository to provide CRUD operations and custom query methods for TaxRate entities.
 * </p>
 */
@Repository
public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {
    List<TaxRate> findByYear(Integer year);
    List<TaxRate> findByStatus(FilingStatus status);
    List<TaxRate> findByYearAndStatus(Integer year, FilingStatus status);

    List<TaxRate> findByYearAndStatusAndRangeStartLessThan(
            Integer year, FilingStatus status, BigDecimal rangeStart
    );

    @Query("SELECT MAX(t.year) FROM TaxRate t")
    Optional<Integer> findMaxYear();

    void deleteByYear(Integer year);

    boolean existsByYear(Integer year);
}

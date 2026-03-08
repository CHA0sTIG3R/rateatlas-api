package com.project.marginal.tax.calculator.repository;

import com.project.marginal.tax.calculator.entity.IngestMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestMetadataRepository extends JpaRepository<IngestMetadata, Integer> {
    // single row, always id=1
}
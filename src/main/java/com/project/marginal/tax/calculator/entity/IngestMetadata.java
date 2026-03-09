package com.project.marginal.tax.calculator.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ingest_metadata")
@NoArgsConstructor
@Getter
@Setter
public class IngestMetadata {

    @Id
    private Integer id;

    @Column(name = "last_seen_page_update")
    private LocalDate lastSeenPageUpdate;

    @Column(name = "last_ingested_at")
    private OffsetDateTime lastIngestedAt;

    @Column(name = "ingest_run_count")
    private Integer ingestRunCount;

    @Column(name = "ingest_skip_count")
    private Integer ingestSkipCount;
}
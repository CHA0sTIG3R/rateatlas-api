package com.project.marginal.tax.calculator.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /** Last successful source check (ingest or unchanged-page skip). Nullable until the V7 contract step. */
    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    /** Last check attempt of any outcome. Nullable until the V7 contract step. */
    @Column(name = "last_attempted_at")
    private OffsetDateTime lastAttemptedAt;

    /** Outcome of the most recent attempt, stored as the Postgres enum {@code ingest_check_status}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "last_check_status")
    private IngestCheckStatus lastCheckStatus;
}
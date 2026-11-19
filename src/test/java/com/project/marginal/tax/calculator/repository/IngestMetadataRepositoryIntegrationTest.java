package com.project.marginal.tax.calculator.repository;

import com.project.marginal.tax.calculator.entity.IngestCheckStatus;
import com.project.marginal.tax.calculator.entity.IngestMetadata;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real Flyway migrations (no Hibernate DDL) and validates the entity against them,
 * mirroring production's {@code spring.jpa.hibernate.ddl-auto=validate}.
 * <p>
 * Test-managed transactions are disabled: Flyway migrates on its own connection, so seeded
 * rows must be committed before V7 runs, and an open test transaction would block its ALTER TABLE.
 * </p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IngestMetadataRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    private static final OffsetDateTime LAST_INGESTED_AT =
            OffsetDateTime.of(2026, 2, 21, 8, 34, 12, 0, ZoneOffset.UTC);

    @Autowired private IngestMetadataRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    /** Rebuilds the schema to V6, seeds a pre-V7 row, then applies V7 so the backfill runs on real data. */
    @BeforeEach
    void migrateFromV6WithExistingRow() {
        Flyway base = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        base.clean();
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();

        jdbc.update("""
                INSERT INTO ingest_metadata
                    (id, last_seen_page_update, last_ingested_at, ingest_run_count, ingest_skip_count)
                VALUES (1, ?, ?, 3, 30)""",
                LocalDate.of(2026, 2, 20), LAST_INGESTED_AT);

        base.migrate();
    }

    @Test
    void v7_backfillsCheckFieldsFromLastIngestedAt() {
        IngestMetadata m = repository.findById(1).orElseThrow();

        assertEquals(LAST_INGESTED_AT.toInstant(), m.getLastCheckedAt().toInstant());
        assertEquals(LAST_INGESTED_AT.toInstant(), m.getLastAttemptedAt().toInstant());
        assertEquals(IngestCheckStatus.SUCCESS, m.getLastCheckStatus());
        assertEquals(LAST_INGESTED_AT.toInstant(), m.getLastIngestedAt().toInstant(),
                "V7 must not touch last_ingested_at");
    }

    @Test
    void checkStatus_roundTripsThroughPostgresEnum() {
        IngestMetadata m = repository.findById(1).orElseThrow();
        m.setLastCheckStatus(IngestCheckStatus.FAILED);
        repository.saveAndFlush(m);

        String stored = jdbc.queryForObject(
                "SELECT last_check_status::text FROM ingest_metadata WHERE id = 1", String.class);
        assertEquals("FAILED", stored);
        assertEquals(IngestCheckStatus.FAILED, repository.findById(1).orElseThrow().getLastCheckStatus());
    }

    @Test
    void checkStatus_rejectsValuesOutsideEnum() {
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE ingest_metadata SET last_check_status = 'SKIPPED' WHERE id = 1"));
    }

    @Test
    void expandStep_preV7IngestorUpsertStillSucceeds() {
        // Exact statement shape used by rateatlas-ingest before it knows about the V7 columns.
        jdbc.update("""
                INSERT INTO ingest_metadata (id, last_seen_page_update, last_ingested_at, ingest_run_count, ingest_skip_count)
                VALUES (1, ?, ?, 1, 0)
                ON CONFLICT (id) DO UPDATE SET
                    last_seen_page_update = EXCLUDED.last_seen_page_update,
                    last_ingested_at = EXCLUDED.last_ingested_at,
                    ingest_run_count = EXCLUDED.ingest_run_count + ingest_metadata.ingest_run_count,
                    ingest_skip_count = EXCLUDED.ingest_skip_count + ingest_metadata.ingest_skip_count""",
                LocalDate.of(2026, 11, 1), OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(4, repository.findById(1).orElseThrow().getIngestRunCount());
    }

    @Test
    void expandStep_nullCheckFieldsAreReadableAsNull() {
        jdbc.update("""
                UPDATE ingest_metadata
                   SET last_checked_at = NULL, last_attempted_at = NULL, last_check_status = NULL
                 WHERE id = 1""");

        IngestMetadata m = repository.findById(1).orElseThrow();
        assertNull(m.getLastCheckedAt());
        assertNull(m.getLastAttemptedAt());
        assertNull(m.getLastCheckStatus());
    }
}

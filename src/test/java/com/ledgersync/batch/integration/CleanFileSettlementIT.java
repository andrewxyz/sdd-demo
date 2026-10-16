package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.entity.SettlementRun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 1 (P1) — quickstart Scenario 1.
 *
 * <p>Proves FR-001, FR-002, FR-024, FR-026, FR-029, SC-001.
 */
class CleanFileSettlementIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("a file whose header matches its rows settles, and every balance moves by its delta")
    void settles_a_clean_file() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");
        seedAccount(SettlementFixtures.ACCOUNT_B, "500.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "250.00")
                .debit(SettlementFixtures.ACCOUNT_A, "300.00")
                .debit(SettlementFixtures.ACCOUNT_B, "100.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-01.csv");

        UUID executionId = startRun("txn-20261016-01.csv.pgp", "fp-clean-001",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        // 1000 + 250 - 300
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("950.00"));
        // 500 - 100
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_B))
                .isEqualByComparingTo(new BigDecimal("400.00"));

        SettlementRun run = reload(executionId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SETTLED);
        assertThat(run.getRecordsRejected()).isZero();
        assertThat(run.getRecordsRead()).isEqualTo(3L);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getObservedNetTotal()).isEqualByComparingTo(run.getDeclaredNetTotal());
    }

    @Test
    @DisplayName("a zero-record file settles to no change rather than erroring")
    void settles_an_empty_file() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");

        Path file = SettlementFixtures.file().writeTo(workDir, "txn-20261016-02.csv");
        UUID executionId = startRun("txn-20261016-02.csv.pgp", "fp-empty-002",
                SettlementFixtures.BUSINESS_DATE, 0L, "0.00");

        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(reload(executionId).getStatus()).isEqualTo(RunStatus.SETTLED);
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("several rows on one account aggregate into a single balance movement")
    void aggregates_multiple_rows_per_account() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "10.00")
                .credit(SettlementFixtures.ACCOUNT_A, "20.00")
                .debit(SettlementFixtures.ACCOUNT_A, "5.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-03.csv");

        UUID executionId = startRun("txn-20261016-03.csv.pgp", "fp-agg-003",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("25.00"));
    }
}

package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.entity.SettlementRun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 2 (P2) — quickstart Scenario 2.
 *
 * <p>Proves FR-019, FR-020, FR-021, FR-022, SC-005.
 */
class ReconciliationFailureIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("a net total mismatch leaves EVERY balance untouched, not just the ones named")
    void an_unbalanced_file_moves_nothing() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");
        seedAccount(SettlementFixtures.ACCOUNT_B, "500.00");
        // A third account the file never mentions. A partial write would leave the named
        // accounts wrong and this one right, so asserting over all three is what catches it.
        seedAccount("UNTOUCHEDACCOUNT0001", "42.00");
        Map<String, BigDecimal> before = snapshotBalances();

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "250.00")
                .debit(SettlementFixtures.ACCOUNT_B, "100.00")
                .declaringTotal("12500.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-04.csv");

        UUID executionId = startRun("txn-20261016-04.csv.pgp", "fp-mismatch-004",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(snapshotBalances()).isEqualTo(before);

        SettlementRun run = reload(executionId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFailureReason()).contains("net total");
        // FR-022: the alert needs all four figures, so all four must be persisted.
        assertThat(run.getDeclaredNetTotal()).isEqualByComparingTo(new BigDecimal("12500.00"));
        assertThat(run.getObservedNetTotal()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("a record count mismatch fails and records both counts")
    void a_short_file_fails_on_count() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");
        Map<String, BigDecimal> before = snapshotBalances();

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "10.00")
                .credit(SettlementFixtures.ACCOUNT_A, "10.00")
                .declaringCount(1000L);
        Path file = fixture.writeTo(workDir, "txn-20261016-05.csv");

        UUID executionId = startRun("txn-20261016-05.csv.pgp", "fp-count-005",
                SettlementFixtures.BUSINESS_DATE, 1000L, fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(snapshotBalances()).isEqualTo(before);
        SettlementRun run = reload(executionId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getDeclaredCount()).isEqualTo(1000L);
        assertThat(run.getObservedCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("the database refuses a SETTLED row that does not satisfy the invariant")
    void the_settled_invariant_is_enforced_by_the_schema() {
        UUID executionId = startRun("txn-20261016-06.csv.pgp", "fp-invariant-006",
                SettlementFixtures.BUSINESS_DATE, 10L, "100.00");

        // Belt and braces: even if a code path forgot the rule, the CHECK constraint from
        // changeset 001 stops a mis-stated SETTLED row reaching the table.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE settlement_run SET status = 'SETTLED', completed_at = now(), "
                        + "observed_count = 9, observed_net_total = 100.00 WHERE execution_id = ?",
                executionId))
                .hasMessageContaining("ck_settlement_run_settled_invariant");
    }

    private Map<String, BigDecimal> snapshotBalances() {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT account_number, balance FROM customer_balance ORDER BY 1",
                rs -> {
                    balances.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return balances;
    }
}

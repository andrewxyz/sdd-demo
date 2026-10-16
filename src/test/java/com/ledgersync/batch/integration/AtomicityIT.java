package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.BalanceApplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-025 / SC-005 — quickstart Scenario 6.
 *
 * <p>Fault is injected at the transaction level rather than by killing the process. A manual
 * kill is slow, non-deterministic, and cannot run in CI — and this is the property least
 * affordable to leave untested, since a half-applied ledger is the failure the whole design is
 * built to prevent.
 */
class AtomicityIT extends AbstractPostgresIT {

    @Autowired
    private BalanceApplier balanceApplier;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a failure during apply leaves every balance untouched, never half-updated")
    void apply_is_all_or_nothing() {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");
        seedAccount(SettlementFixtures.ACCOUNT_B, "200.00");
        UUID executionId = startRun("txn-20261016-30.csv.pgp", "fp-atomic-030",
                SettlementFixtures.BUSINESS_DATE, 2L, "30.00");
        stageRow(executionId, SettlementFixtures.ACCOUNT_A, "10.00");
        stageRow(executionId, SettlementFixtures.ACCOUNT_B, "20.00");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                balanceApplier.apply(executionId, Instant.now());
                throw new IllegalStateException("injected failure mid-apply");
            }
        })).hasMessage("injected failure mid-apply");

        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_B)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("a successful apply moves one row per account touched, not one per transaction")
    void apply_aggregates_before_writing() {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");
        UUID executionId = startRun("txn-20261016-31.csv.pgp", "fp-atomic-031",
                SettlementFixtures.BUSINESS_DATE, 3L, "60.00");
        stageRow(executionId, SettlementFixtures.ACCOUNT_A, "10.00");
        stageRow(executionId, SettlementFixtures.ACCOUNT_A, "20.00");
        stageRow(executionId, SettlementFixtures.ACCOUNT_A, "30.00");

        int accountsUpdated = balanceApplier.apply(executionId, Instant.now());

        // Three transactions, one account: the write is sized by accounts, which is what makes
        // single-transaction atomicity affordable at 500,000 rows (research R3).
        assertThat(accountsUpdated).isEqualTo(1);
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    private void stageRow(UUID executionId, String account, String signedAmount) {
        jdbcTemplate.update("""
                INSERT INTO settlement_staged_transaction
                  (execution_id, source_row_number, account_number, transaction_type,
                   amount, signed_amount, transaction_timestamp)
                VALUES (?, ?, ?, 'CREDIT', ?, ?, now())
                """,
                executionId, 1L, account,
                new BigDecimal(signedAmount).abs(), new BigDecimal(signedAmount));
    }
}

package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FR-025b / SC-011 — the test that closes analysis finding G1.
 *
 * <p>Ordering is satisfied by construction today: the apply step sums deltas per account, and
 * addition is commutative, so how work was divided cannot change the result. That reasoning
 * stops holding the moment an order-sensitive rule (an overdraft check, say) enters the apply
 * path — and nothing else in the suite would notice. This test would.
 */
class ReproducibilityIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("the same file yields identical balances regardless of chunk boundaries")
    void closing_balances_do_not_depend_on_work_division() throws Exception {
        SettlementFixtures fixture = interleavedFixture();
        Path file = fixture.writeTo(workDir, "txn-20261016-20.csv");

        BigDecimal firstRun = settleAndReadBalance(file, fixture, "fp-repro-a");
        BigDecimal secondRun = settleAndReadBalance(file, fixture, "fp-repro-b");

        assertThat(secondRun)
                .as("replaying the same file against the same opening balance must agree")
                .isEqualByComparingTo(firstRun);
    }

    @Test
    @DisplayName("a file whose rows interleave two accounts settles each to its own net delta")
    void interleaved_accounts_each_net_correctly() throws Exception {
        resetDatabase();
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");
        seedAccount(SettlementFixtures.ACCOUNT_B, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file();
        for (int i = 0; i < 50; i++) {
            fixture.credit(SettlementFixtures.ACCOUNT_A, "2.00");
            fixture.debit(SettlementFixtures.ACCOUNT_B, "1.00");
        }
        Path file = fixture.writeTo(workDir, "txn-20261016-21.csv");

        UUID executionId = startRun("txn-20261016-21.csv.pgp", "fp-interleaved",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_B))
                .isEqualByComparingTo(new BigDecimal("-50.00"));
    }

    private BigDecimal settleAndReadBalance(
            Path file, SettlementFixtures fixture, String fingerprint) throws Exception {
        resetDatabase();
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");

        UUID executionId = startRun(fingerprint + ".csv.pgp", fingerprint,
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);
        return balanceOf(SettlementFixtures.ACCOUNT_A);
    }

    private static SettlementFixtures interleavedFixture() {
        SettlementFixtures fixture = SettlementFixtures.file();
        // Enough rows to span several chunks at the configured size of 1000.
        for (int i = 0; i < 2500; i++) {
            fixture.credit(SettlementFixtures.ACCOUNT_A, "3.00");
            if (i % 3 == 0) {
                fixture.debit(SettlementFixtures.ACCOUNT_A, "1.00");
            }
        }
        return fixture;
    }
}

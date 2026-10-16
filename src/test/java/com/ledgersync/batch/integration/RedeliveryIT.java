package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.ingestion.dto.DeliveryCandidate;
import com.ledgersync.batch.ingestion.dto.Eligibility;
import com.ledgersync.batch.ingestion.LandingZoneScanner;
import com.ledgersync.batch.persistence.entity.RunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * User Story 4 (P4) — quickstart Scenario 5.
 *
 * <p>Clarification Q4 in action: fingerprint and business date answer different questions, so a
 * corrected file gets through while a redelivery stays harmless.
 *
 * <p>Proves FR-006, FR-006a, FR-007, FR-027, SC-008, SC-009.
 */
class RedeliveryIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    @Autowired
    private LandingZoneScanner scanner;

    @Test
    @DisplayName("fail, then an identical redelivery is ignored, then a correction settles")
    void the_full_recovery_sequence() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");

        // 1. An unbalanced file fails.
        SettlementFixtures bad = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "100.00")
                .declaringTotal("999.00");
        Path badFile = bad.writeTo(workDir, "txn-20261016-01.csv");
        UUID failedExecution = startRun("txn-20261016-01.csv.pgp", "fp-identical",
                SettlementFixtures.BUSINESS_DATE, bad.declaredCount(), bad.declaredTotal());
        runJobExpectingFailure(failedExecution, badFile, SettlementFixtures.BUSINESS_DATE);
        assertThat(reload(failedExecution).getStatus()).isEqualTo(RunStatus.FAILED);

        // 2. The identical file arrives again — recognised by fingerprint, no new run.
        assertThat(scanner.assess(candidate(badFile, "fp-identical")))
                .isEqualTo(Eligibility.DUPLICATE_IGNORED);

        // 3. A corrected file for the same date is a new fingerprint on a FAILED date: process.
        SettlementFixtures good = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "100.00");
        Path goodFile = good.writeTo(workDir, "txn-20261016-02.csv");
        assertThat(scanner.assess(candidate(goodFile, "fp-corrected")))
                .isEqualTo(Eligibility.PROCESS);

        UUID correctedExecution = startRun("txn-20261016-02.csv.pgp", "fp-corrected",
                SettlementFixtures.BUSINESS_DATE, good.declaredCount(), good.declaredTotal());
        runJob(correctedExecution, goodFile, SettlementFixtures.BUSINESS_DATE);

        assertThat(reload(correctedExecution).getStatus()).isEqualTo(RunStatus.SETTLED);
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("1100.00"));

        // 4. A different file for the now-SETTLED date waits for an operator.
        assertThat(scanner.assess(candidate(goodFile, "fp-third-file")))
                .isEqualTo(Eligibility.SETTLED_DATE_CONFLICT);

        // SC-009: both runs remain queryable, each with its own execution id.
        assertThat(runRepository.findByBusinessDateOrderByStartedAtAsc(
                SettlementFixtures.BUSINESS_DATE)).hasSize(2);
    }

    @Test
    @DisplayName("SC-008: the balance moves once, however many times the file is redelivered")
    void a_redelivery_cannot_apply_twice() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "50.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-03.csv");

        UUID executionId = startRun("txn-20261016-03.csv.pgp", "fp-once",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(scanner.assess(candidate(file, "fp-once")))
                .isEqualTo(Eligibility.DUPLICATE_IGNORED);
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A))
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    private static DeliveryCandidate candidate(Path file, String fingerprint) {
        return new DeliveryCandidate(
                file, file.resolveSibling(file.getFileName() + ".done"),
                SettlementFixtures.BUSINESS_DATE, fingerprint);
    }
}

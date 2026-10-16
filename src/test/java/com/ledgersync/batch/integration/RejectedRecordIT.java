package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.entity.RejectedRecordEntity;
import com.ledgersync.batch.persistence.entity.RunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Story 3 (P3) — quickstart Scenario 3.
 *
 * <p>Proves FR-013, FR-014, FR-016, FR-020, FR-031, SC-003, SC-004.
 */
class RejectedRecordIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("every defect is reported from one pass, including the one on the last row")
    void reports_all_defects_including_the_last_row() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "1000.00");

        SettlementFixtures fixture =
                SettlementFixtures.withDefectsIncludingLastRow(1000, 7);
        Path file = fixture.writeTo(workDir, "txn-20261016-07.csv");

        UUID executionId = startRun("txn-20261016-07.csv.pgp", "fp-defects-007",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        // Rejects make the file unreconcilable (clarification Q1), so the job fails — but only
        // after it has read to the end.
        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        List<RejectedRecordEntity> rejects =
                rejectedRepository.findByExecutionIdOrderBySourceRowNumberAsc(executionId);

        assertThat(rejects).hasSize(7);
        // The assertion that matters: if reading had stopped at the first defect, the highest
        // row number here would be small rather than near the end of the file.
        assertThat(rejects.get(rejects.size() - 1).getSourceRowNumber())
                .isGreaterThan(900L);

        assertThat(reload(executionId).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(balanceOf(SettlementFixtures.ACCOUNT_A)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("each reject names its field and reason in plain language")
    void records_a_usable_reason() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "10.00")
                .malformed("D,,10.00,CREDIT," + SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
        Path file = fixture.writeTo(workDir, "txn-20261016-08.csv");

        UUID executionId = startRun("txn-20261016-08.csv.pgp", "fp-reason-008",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        RejectedRecordEntity reject =
                rejectedRepository.findByExecutionIdOrderBySourceRowNumberAsc(executionId).get(0);

        assertThat(reject.getFailingField()).isEqualTo("accountNumber");
        assertThat(reject.getRejectionReason()).isNotBlank();
        assertThat(reject.getRejectedAt()).isNotNull();
    }

    @Test
    @DisplayName("FR-031: the stored excerpt carries no unmasked account number")
    void masks_account_numbers_in_the_excerpt() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .detail(SettlementFixtures.ACCOUNT_A, "10.005", "CREDIT",
                        SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
        Path file = fixture.writeTo(workDir, "txn-20261016-09.csv");

        UUID executionId = startRun("txn-20261016-09.csv.pgp", "fp-mask-009",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        List<RejectedRecordEntity> rejects =
                rejectedRepository.findByExecutionIdOrderBySourceRowNumberAsc(executionId);
        assertThat(rejects).isNotEmpty();
        assertThat(rejects.get(0).getRawRowExcerpt())
                .doesNotContain(SettlementFixtures.ACCOUNT_A);
    }

    @Test
    @DisplayName("FR-016: rejects are retrievable by business date as well as execution id")
    void rejects_are_queryable_by_business_date() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");

        SettlementFixtures fixture = SettlementFixtures.file()
                .malformed("D,,1.00,CREDIT," + SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
        Path file = fixture.writeTo(workDir, "txn-20261016-10.csv");

        UUID executionId = startRun("txn-20261016-10.csv.pgp", "fp-bydate-010",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(rejectedRepository.findByBusinessDate(SettlementFixtures.BUSINESS_DATE))
                .isNotEmpty();
    }
}

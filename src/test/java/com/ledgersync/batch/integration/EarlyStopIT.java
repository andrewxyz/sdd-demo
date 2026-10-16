package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FR-015 / FR-015a — quickstart Scenario 4.
 *
 * <p>Both halves of the rule matter, and they pull in opposite directions: the ratio must trip on
 * a hopeless file, and the floor must stop it tripping on a small one.
 */
class EarlyStopIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    /**
     * 2,000 rows is enough to cross the 1,000-record floor and trip the ratio. It used to be
     * 5,000, which only made the test slower: every skip costs a chunk replay, so a reject-dense
     * fixture is quadratic in rows, and nothing after the threshold trips is ever read anyway.
     */
    private static final int HOPELESS_ROWS = 2000;

    @Test
    @DisplayName("a file that is ~40% malformed is abandoned rather than read to the end")
    void abandons_a_hopeless_file() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file();
        for (int i = 1; i <= HOPELESS_ROWS; i++) {
            if (i % 5 <= 1) {
                fixture.malformed("D,,1.00,CREDIT,"
                        + SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
            } else {
                fixture.credit(SettlementFixtures.ACCOUNT_A, "1.00");
            }
        }
        Path file = fixture.writeTo(workDir, "txn-20261016-13.csv");

        UUID executionId = startRun("txn-20261016-13.csv.pgp", "fp-earlystop-013",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        long rejected = rejectedRepository.countByExecutionId(executionId);
        assertThat(rejected)
                .as("reading should stop part-way, not report every defect in the file")
                .isLessThan(HOPELESS_ROWS * 2L / 5);
    }

    @Test
    @DisplayName("a 40-row file with 30 rejects never reaches the floor, so it reports all 30")
    void the_floor_protects_small_files() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file();
        for (int i = 1; i <= 40; i++) {
            if (i <= 30) {
                fixture.malformed("D,,1.00,CREDIT,"
                        + SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
            } else {
                fixture.credit(SettlementFixtures.ACCOUNT_A, "1.00");
            }
        }
        Path file = fixture.writeTo(workDir, "txn-20261016-14.csv");

        UUID executionId = startRun("txn-20261016-14.csv.pgp", "fp-floor-014",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        runJobExpectingFailure(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(rejectedRepository.countByExecutionId(executionId))
                .as("75% rejected, but below the 1,000-record floor the ratio must not apply")
                .isEqualTo(30L);
    }
}

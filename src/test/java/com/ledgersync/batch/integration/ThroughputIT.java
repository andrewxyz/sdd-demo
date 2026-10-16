package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.entity.RunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-001: a 500,000-transaction file settles within 30 minutes of run start.
 *
 * <p>Tagged {@code slow} and excluded from the default verify run — it generates a large fixture
 * and is meant for a nightly or pre-release pipeline, not every pull request.
 *
 * <p>The measure deliberately excludes queue wait: runs are serial (FR-025a), so a file waiting
 * its turn says nothing about throughput.
 */
@Tag("slow")
class ThroughputIT extends AbstractPostgresIT {

    private static final int TRANSACTIONS = 500_000;
    private static final Duration BUDGET = Duration.ofMinutes(30);

    @TempDir
    Path workDir;

    @Test
    @DisplayName("500,000 transactions settle inside the 30-minute budget")
    void settles_a_full_days_volume_within_budget() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "0.00");
        seedAccount(SettlementFixtures.ACCOUNT_B, "0.00");

        SettlementFixtures fixture = SettlementFixtures.file();
        for (int i = 0; i < TRANSACTIONS; i++) {
            String account = i % 2 == 0
                    ? SettlementFixtures.ACCOUNT_A
                    : SettlementFixtures.ACCOUNT_B;
            fixture.credit(account, "1.00");
        }
        Path file = fixture.writeTo(workDir, "txn-20261016-99.csv");

        UUID executionId = startRun("txn-20261016-99.csv.pgp", "fp-throughput-099",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());

        Instant started = Instant.now();
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(reload(executionId).getStatus()).isEqualTo(RunStatus.SETTLED);
        assertThat(elapsed).isLessThan(BUDGET);
    }
}

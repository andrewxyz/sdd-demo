package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.entity.SettlementRun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SC-008 / research R8 — the fingerprint guarantee belongs to the database.
 *
 * <p>An application-level "have I seen this?" check has a window between the read and the write.
 * Two deliveries landing inside that window would both pass and both apply. The unique index
 * closes it, which is why this is asserted against the schema rather than against the scanner.
 */
class FingerprintConcurrencyIT extends AbstractPostgresIT {

    @Test
    @DisplayName("a second run with the same fingerprint is rejected by the unique index")
    void the_unique_index_is_the_guarantee() {
        runRepository.save(new SettlementRun(
                UUID.randomUUID(), "txn-20261016-01.csv.pgp", "fp-duplicate",
                SettlementFixtures.BUSINESS_DATE, Instant.now()));

        assertThatThrownBy(() -> runRepository.saveAndFlush(new SettlementRun(
                UUID.randomUUID(), "txn-20261016-02.csv.pgp", "fp-duplicate",
                SettlementFixtures.BUSINESS_DATE, Instant.now())))
                .hasMessageContaining("uq_settlement_run_fingerprint");
    }

    @Test
    @DisplayName("different fingerprints for the same business date are both allowed")
    void a_correction_is_not_blocked_by_the_index() {
        runRepository.save(new SettlementRun(
                UUID.randomUUID(), "txn-20261016-01.csv.pgp", "fp-original",
                SettlementFixtures.BUSINESS_DATE, Instant.now()));
        runRepository.saveAndFlush(new SettlementRun(
                UUID.randomUUID(), "txn-20261016-02.csv.pgp", "fp-corrected",
                SettlementFixtures.BUSINESS_DATE, Instant.now()));

        assertThat(runRepository.findByBusinessDateOrderByStartedAtAsc(
                SettlementFixtures.BUSINESS_DATE)).hasSize(2);
    }

    @Test
    @DisplayName("execution ids are unique too, so correlation can never be ambiguous")
    void execution_ids_are_unique() {
        UUID shared = UUID.randomUUID();
        runRepository.save(new SettlementRun(
                shared, "a.csv.pgp", "fp-a", SettlementFixtures.BUSINESS_DATE, Instant.now()));

        assertThatThrownBy(() -> runRepository.saveAndFlush(new SettlementRun(
                shared, "b.csv.pgp", "fp-b", SettlementFixtures.BUSINESS_DATE, Instant.now())))
                .hasMessageContaining("uq_settlement_run_execution_id");
    }
}

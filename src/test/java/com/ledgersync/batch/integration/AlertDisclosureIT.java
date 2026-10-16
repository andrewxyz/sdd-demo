package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgersync.batch.alerting.AlertDispatcher;
import com.ledgersync.batch.alerting.SettlementAlertPublisher;
import com.ledgersync.batch.alerting.dto.AlertPayload;
import com.ledgersync.batch.alerting.dto.AlertType;
import com.ledgersync.batch.domain.ReconciliationResult;
import com.ledgersync.batch.fixtures.SettlementFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * FR-030 and SC-006 — contracts/alert-hook.md sections 2 to 4.
 *
 * <p>Covers both the disclosure constraints and the latency assertion that analysis finding G2
 * flagged as missing.
 */
@Import(AlertDisclosureIT.CapturingPublisherConfig.class)
class AlertDisclosureIT extends AbstractPostgresIT {

    @TestConfiguration
    static class CapturingPublisherConfig {
        @Bean
        @Primary
        CapturingPublisher capturingPublisher() {
            return new CapturingPublisher();
        }
    }

    static class CapturingPublisher implements SettlementAlertPublisher {
        final List<AlertPayload> published = new CopyOnWriteArrayList<>();
        final List<Instant> publishedAt = new CopyOnWriteArrayList<>();

        @Override
        public void publish(AlertPayload payload) {
            published.add(payload);
            publishedAt.add(Instant.now());
        }
    }

    @Autowired
    private CapturingPublisher publisher;

    @Autowired
    private AlertDispatcher dispatcher;

    /**
     * Built here rather than injected. This service has no web starter, so the context holds no
     * {@code ObjectMapper} bean — {@link com.ledgersync.batch.alerting.LoggingAlertAdapter}
     * builds its own for the same reason. Configured identically so this test serialises the
     * payload exactly as the adapter would.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void clearCaptured() {
        publisher.published.clear();
        publisher.publishedAt.clear();
    }

    @Test
    @DisplayName("FR-030: no account number, row amount, or surrogate id reaches the channel")
    void the_payload_discloses_nothing_customer_identifying() throws Exception {
        UUID executionId = UUID.randomUUID();
        dispatcher.reconciliationMismatch(
                executionId, "txn-20261016-01.csv.pgp", SettlementFixtures.BUSINESS_DATE,
                mismatch(), 3L, false);

        assertThat(publisher.published).hasSize(1);
        String json = objectMapper.writeValueAsString(publisher.published.get(0));

        assertThat(json).doesNotContain(SettlementFixtures.ACCOUNT_A);
        assertThat(json).doesNotContain(SettlementFixtures.ACCOUNT_B);
        // The payload carries file-level totals only; no surrogate key appears anywhere.
        assertThat(json).contains(executionId.toString());
        assertThat(json).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("FR-022: the alert states declared and observed count and net total")
    void the_alert_carries_all_four_figures() {
        dispatcher.reconciliationMismatch(
                UUID.randomUUID(), "txn-20261016-01.csv.pgp", SettlementFixtures.BUSINESS_DATE,
                mismatch(), 3L, false);

        AlertPayload.Reconciliation figures = publisher.published.get(0).reconciliation();
        assertThat(figures.declaredCount()).isEqualTo(3L);
        assertThat(figures.observedCount()).isEqualTo(3L);
        assertThat(figures.declaredNetTotal()).isEqualByComparingTo("-150.00");
        assertThat(figures.observedNetTotal()).isEqualByComparingTo("-149.00");
    }

    @Test
    @DisplayName("FR-015a: an early-stopped run says plainly that the reject list is partial")
    void an_early_stop_alert_admits_truncation() {
        dispatcher.earlyStop(
                UUID.randomUUID(), "txn-20261016-13.csv.pgp", SettlementFixtures.BUSINESS_DATE,
                1300L, 1800L);

        AlertPayload payload = publisher.published.get(0);
        assertThat(payload.alertType()).isEqualTo(AlertType.EARLY_STOP_THRESHOLD);
        assertThat(payload.readAbandoned()).isTrue();
        assertThat(payload.recordsRead()).isEqualTo(1800L);
        assertThat(payload.summary()).contains("partial");
    }

    @Test
    @DisplayName("SC-006: the alert is published within 2 minutes of detection")
    void alerts_are_published_promptly() {
        Instant before = Instant.now();

        dispatcher.reconciliationMismatch(
                UUID.randomUUID(), "txn-20261016-01.csv.pgp", SettlementFixtures.BUSINESS_DATE,
                mismatch(), 3L, false);

        assertThat(publisher.publishedAt).hasSize(1);
        assertThat(Duration.between(before, publisher.publishedAt.get(0)))
                .isLessThan(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("every payload carries readAbandoned and recordsRead, whatever its type")
    void truncation_fields_are_always_present() {
        dispatcher.simple(AlertType.DELIVERY_OVERDUE, UUID.randomUUID(), "(no file received)",
                SettlementFixtures.BUSINESS_DATE, "PENDING", "nothing arrived");

        AlertPayload payload = publisher.published.get(0);
        assertThat(payload.readAbandoned()).isFalse();
        assertThat(payload.recordsRead()).isZero();
    }

    private static ReconciliationResult mismatch() {
        return new ReconciliationResult(
                false, 3L, 3L, new BigDecimal("-150.00"), new BigDecimal("-149.00"), 0L,
                "net total does not match the header");
    }
}

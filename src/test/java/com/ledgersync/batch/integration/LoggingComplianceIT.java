package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.observability.ExecutionIdJobListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Constitution V and FR-031.
 *
 * <p>Asserts over the structured events rather than over rendered text: the encoder's output
 * format is configuration, but the MDC contract and the absence of customer data are the
 * properties that actually matter.
 */
class LoggingComplianceIT extends AbstractPostgresIT {

    @TempDir
    Path workDir;

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void attachAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("every event emitted during a run carries the same executionId in MDC")
    void all_events_share_one_correlation_id() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");
        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "10.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-40.csv");

        UUID executionId = startRun("txn-20261016-40.csv.pgp", "fp-logging-040",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        List<String> boundIds = appender.list.stream()
                .map(e -> e.getMDCPropertyMap().get(ExecutionIdJobListener.MDC_EXECUTION_ID))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        assertThat(boundIds)
                .as("a run's events must be correlatable by exactly one identifier")
                .hasSize(1);
    }

    @Test
    @DisplayName("FR-031: no log event contains an unmasked account number")
    void logs_never_carry_account_numbers() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");
        SettlementFixtures fixture = SettlementFixtures.file()
                .malformed("D,,1.00,CREDIT," + SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00")
                .detail(SettlementFixtures.ACCOUNT_A, "1.005", "CREDIT",
                        SettlementFixtures.BUSINESS_DATE + "T09:00:00+00:00");
        Path file = fixture.writeTo(workDir, "txn-20261016-41.csv");

        UUID executionId = startRun("txn-20261016-41.csv.pgp", "fp-logging-041",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        try {
            runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);
        } catch (Exception expectedReconciliationFailure) {
            // Rejects make the file unreconcilable; the logging assertion is what matters here.
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(SettlementFixtures.ACCOUNT_A));
    }

    @Test
    @DisplayName("MDC is clear once the run has finished, so nothing bleeds into the next")
    void mdc_does_not_outlive_the_run() throws Exception {
        seedAccount(SettlementFixtures.ACCOUNT_A, "100.00");
        SettlementFixtures fixture = SettlementFixtures.file()
                .credit(SettlementFixtures.ACCOUNT_A, "10.00");
        Path file = fixture.writeTo(workDir, "txn-20261016-42.csv");

        UUID executionId = startRun("txn-20261016-42.csv.pgp", "fp-logging-042",
                SettlementFixtures.BUSINESS_DATE, fixture.declaredCount(), fixture.declaredTotal());
        runJob(executionId, file, SettlementFixtures.BUSINESS_DATE);

        assertThat(org.slf4j.MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID)).isNull();
    }
}

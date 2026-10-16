package com.ledgersync.batch.alerting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.ledgersync.batch.alerting.dto.AlertPayload;
import com.ledgersync.batch.alerting.dto.AlertType;
import com.ledgersync.batch.domain.ReconciliationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds and dispatches alerts.
 *
 * <p><strong>Dispatch happens outside the run transaction</strong> (research R11). Publishing
 * inside it is the subtle trap: a rollback would discard the alert about the very failure that
 * caused the rollback, so the run fails and nobody is told. Callers invoke this after the
 * outcome is already committed.
 *
 * <p>A dispatch failure is logged at ERROR and swallowed. The ledger outcome is decided; it must
 * not change because a notification channel was unavailable
 * (contracts/alert-hook.md section 4).
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final SettlementAlertPublisher publisher;
    private final Clock clock;

    public AlertDispatcher(SettlementAlertPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void reconciliationMismatch(
            UUID executionId, String sourceFileName, LocalDate businessDate,
            ReconciliationResult result, long recordsRead, boolean readAbandoned) {
        dispatch(new AlertPayload(
                AlertType.RECONCILIATION_MISMATCH,
                AlertPayload.SEVERITY_URGENT,
                executionId,
                sourceFileName,
                businessDate,
                clock.instant(),
                "FAILED",
                result.failureReason(),
                new AlertPayload.Reconciliation(
                        result.declaredCount(), result.observedCount(),
                        result.declaredNetTotal(), result.observedNetTotal()),
                result.rejectedCount(),
                readAbandoned,
                recordsRead));
    }

    public void earlyStop(
            UUID executionId, String sourceFileName, LocalDate businessDate,
            long rejectedCount, long recordsRead) {
        dispatch(new AlertPayload(
                AlertType.EARLY_STOP_THRESHOLD,
                AlertPayload.SEVERITY_URGENT,
                executionId, sourceFileName, businessDate, clock.instant(), "FAILED",
                // FR-015a: say plainly that the list is partial, so a truncated rejection list is
                // never mistaken for the file's complete set of defects.
                "Reading abandoned after %d records with %d rejected; the rejection list is partial"
                        .formatted(recordsRead, rejectedCount),
                null, rejectedCount, true, recordsRead));
    }

    public void simple(
            AlertType type, UUID executionId, String sourceFileName, LocalDate businessDate,
            String runStatus, String summary) {
        dispatch(new AlertPayload(
                type, AlertPayload.SEVERITY_URGENT, executionId, sourceFileName, businessDate,
                clock.instant(), runStatus, summary, null, 0L, false, 0L));
    }

    private void dispatch(AlertPayload payload) {
        try {
            publisher.publish(payload);
        } catch (RuntimeException e) {
            log.error("Alert dispatch failed for {}; run status is unaffected",
                    payload.idempotencyKey(), e);
        }
    }
}

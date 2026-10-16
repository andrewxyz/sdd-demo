package com.ledgersync.batch.alerting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbound alert payload per contracts/alert-hook.md section 2. Immutable record
 * (Constitution III).
 *
 * <p><strong>Disclosure constraints (Constitution I, FR-030):</strong> this payload carries no
 * account number, no individual row amount, and no surrogate database identifier. The figures in
 * {@link Reconciliation} are file-level totals, which reveal nothing about any single account. A
 * recipient needing row detail queries the rejected-record store by {@code executionId}.
 *
 * <p>{@code readAbandoned} and {@code recordsRead} appear on <em>every</em> alert, not only on
 * {@link AlertType#EARLY_STOP_THRESHOLD}, so a recipient never has to know which alert types can
 * be truncated in order to ask the question (FR-015a).
 */
public record AlertPayload(
        AlertType alertType,
        String severity,
        UUID executionId,
        String sourceFileName,
        LocalDate businessDate,
        Instant detectedAt,
        String runStatus,
        String summary,
        Reconciliation reconciliation,
        long rejectedRecordCount,
        boolean readAbandoned,
        long recordsRead) {

    public static final String SEVERITY_URGENT = "URGENT";

    /** Present on {@link AlertType#RECONCILIATION_MISMATCH}; null otherwise. */
    public record Reconciliation(
            Long declaredCount,
            Long observedCount,
            BigDecimal declaredNetTotal,
            BigDecimal observedNetTotal) {
    }

    public AlertPayload {
        if (alertType == null) {
            throw new IllegalArgumentException("alertType is required on every alert");
        }
        if (executionId == null) {
            throw new IllegalArgumentException("executionId is required on every alert");
        }
        severity = severity == null ? SEVERITY_URGENT : severity;
    }

    /** Idempotency key for an at-least-once channel (contracts/alert-hook.md section 4). */
    public String idempotencyKey() {
        return executionId + ":" + alertType;
    }
}

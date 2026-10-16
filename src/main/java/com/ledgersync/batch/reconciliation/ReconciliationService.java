package com.ledgersync.batch.reconciliation;

import java.math.BigDecimal;

import com.ledgersync.batch.domain.ReconciliationResult;

/**
 * FR-018 to FR-020. Pure arithmetic — no I/O, no framework types, no database.
 *
 * <p>The caller supplies the observed figures; this class decides only whether they agree with
 * what the file declared. Keeping the fetch outside makes the decision unit-testable and keeps
 * the reconciliation layer free of persistence concerns (Constitution II).
 */
public class ReconciliationService {

    /**
     * Compares observed against declared.
     *
     * <p><strong>Clarification Q1:</strong> the comparison is against the <em>full</em> header,
     * unreduced by rejects. A file producing even one rejected record therefore cannot reconcile.
     * That is deliberate: once a row is dropped, the control total can no longer prove nothing was
     * lost, and settling anyway means knowingly booking an incomplete day.
     */
    public ReconciliationResult reconcile(
            long declaredCount,
            BigDecimal declaredNetTotal,
            long observedCount,
            BigDecimal observedNetTotal,
            long rejectedCount) {

        BigDecimal observed = observedNetTotal == null ? BigDecimal.ZERO : observedNetTotal;
        BigDecimal declared = declaredNetTotal == null ? BigDecimal.ZERO : declaredNetTotal;

        boolean countAgrees = declaredCount == observedCount;
        boolean totalAgrees = declared.compareTo(observed) == 0;
        boolean nothingRejected = rejectedCount == 0;
        boolean reconciled = countAgrees && totalAgrees && nothingRejected;

        return new ReconciliationResult(
                reconciled, declaredCount, observedCount, declared, observed, rejectedCount,
                reconciled ? null : describeFailure(countAgrees, totalAgrees, rejectedCount));
    }

    private static String describeFailure(
            boolean countAgrees, boolean totalAgrees, long rejectedCount) {
        StringBuilder reason = new StringBuilder();
        if (rejectedCount > 0) {
            reason.append(rejectedCount)
                    .append(" record(s) rejected, so the file cannot be reconciled against its header");
        }
        if (!countAgrees) {
            appendSeparator(reason);
            reason.append("record count does not match the header");
        }
        if (!totalAgrees) {
            appendSeparator(reason);
            reason.append("net total does not match the header");
        }
        String text = reason.toString();
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    private static void appendSeparator(StringBuilder reason) {
        if (!reason.isEmpty()) {
            reason.append("; ");
        }
    }
}

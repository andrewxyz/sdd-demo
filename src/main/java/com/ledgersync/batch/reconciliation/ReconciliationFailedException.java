package com.ledgersync.batch.reconciliation;

import com.ledgersync.batch.domain.ReconciliationResult;

/**
 * FR-020 / FR-021: the file does not agree with its own header, so no balance may move.
 *
 * <p>Carries the {@link ReconciliationResult} so the alert can state declared and observed
 * figures without recomputing them — a recomputation could disagree with the decision that was
 * actually made.
 */
public class ReconciliationFailedException extends RuntimeException {

    private final transient ReconciliationResult result;

    public ReconciliationFailedException(ReconciliationResult result) {
        super(result.failureReason());
        this.result = result;
    }

    public ReconciliationResult getResult() {
        return result;
    }
}

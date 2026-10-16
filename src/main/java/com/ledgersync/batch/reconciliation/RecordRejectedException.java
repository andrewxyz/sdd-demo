package com.ledgersync.batch.reconciliation;

/**
 * A record that failed a business validation rule (FR-008 to FR-012).
 *
 * <p>Thrown so the step's skip policy can act on it and the skip listener can write a durable
 * rejected-record row. Carries everything that row needs, because by the time the listener runs
 * the item itself is no longer in hand.
 */
public class RecordRejectedException extends RuntimeException {

    private final long sourceRowNumber;
    private final String failingField;
    private final String rawLine;

    public RecordRejectedException(
            long sourceRowNumber, String failingField, String reason, String rawLine) {
        super(reason);
        this.sourceRowNumber = sourceRowNumber;
        this.failingField = failingField;
        this.rawLine = rawLine;
    }

    public long getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getFailingField() {
        return failingField;
    }

    public String getRawLine() {
        return rawLine;
    }
}

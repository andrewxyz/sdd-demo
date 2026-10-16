package com.ledgersync.batch.ingestion;

/**
 * A row that could not even be parsed into a {@code TransactionRecord}.
 *
 * <p>Carries the field, the plain-language reason and the raw line so the skip listener can write
 * a complete rejected-record row (FR-013). The raw line is masked before it is persisted
 * (FR-031) — it is unmasked here only in flight.
 */
public class MalformedRowException extends RuntimeException {

    private final long sourceRowNumber;
    private final String failingField;
    private final String rawLine;

    public MalformedRowException(
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

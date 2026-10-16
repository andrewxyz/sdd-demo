package com.ledgersync.batch.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One customer transaction read from the file. Immutable record (Constitution III).
 *
 * <p>Lives in {@code domain} rather than in {@code ingestion} or {@code reconciliation} because
 * both layers need it. Placing it in either one would make the other depend on it and create the
 * cross-layer edge Constitution II exists to prevent.
 *
 * <p>{@code amount} is always non-negative; {@code type} supplies the direction, and
 * {@link #signedAmount()} applies it.
 */
public record TransactionRecord(
        long sourceRowNumber,
        String accountNumber,
        String type,
        BigDecimal amount,
        Instant timestamp,
        String rawLine) {

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";

    /** FR-018: {@code +amount} for CREDIT, {@code -amount} for DEBIT. */
    public BigDecimal signedAmount() {
        return DEBIT.equals(type) ? amount.negate() : amount;
    }
}

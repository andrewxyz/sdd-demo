package com.ledgersync.batch.domain;

/**
 * A record that failed validation, carried from reconciliation to persistence (FR-013).
 *
 * <p>{@code maskedExcerpt} is already masked by the time it reaches this record — masking happens
 * at write time so plaintext account numbers never reach storage (FR-031).
 */
public record RejectedRecord(
        long sourceRowNumber,
        String failingField,
        String reason,
        String maskedExcerpt) {
}

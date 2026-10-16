package com.ledgersync.batch.domain;

/**
 * Result of validating one record (FR-008 to FR-012).
 *
 * <p>The reason is plain language because an operator reads it, not a developer (FR-013).
 */
public record ValidationOutcome(boolean valid, String failingField, String reason) {

    private static final ValidationOutcome ACCEPTED = new ValidationOutcome(true, null, null);

    /** Named {@code accepted} rather than {@code valid} to leave the accessor {@code valid()} free. */
    public static ValidationOutcome accepted() {
        return ACCEPTED;
    }

    public static ValidationOutcome rejected(String failingField, String reason) {
        return new ValidationOutcome(false, failingField, reason);
    }
}

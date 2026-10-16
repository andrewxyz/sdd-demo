package com.ledgersync.batch.persistence.entity;

/**
 * FR-005: the closed set of run statuses.
 *
 * <p>There is no transition out of {@link #SETTLED} or {@link #FAILED}. A corrected file creates a
 * new run (FR-007); the failed run is retained for audit (SC-009).
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    FAILED,
    SETTLED;

    public boolean isTerminal() {
        return this == FAILED || this == SETTLED;
    }
}

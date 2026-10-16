package com.ledgersync.batch.alerting.dto;

/**
 * Trigger conditions from contracts/alert-hook.md section 1. All are urgent; a run reaching
 * SETTLED emits nothing.
 */
public enum AlertType {

    /** FR-003: the file could not be opened at all. */
    DECRYPTION_FAILED,

    /** FR-002a: a sentinel names a data file that is absent or unreadable. */
    BROKEN_DELIVERY,

    /** FR-002b: no completed delivery for a business date by its deadline. */
    DELIVERY_OVERDUE,

    /** FR-023: header absent or unparseable, so correctness cannot be demonstrated. */
    HEADER_UNREADABLE,

    /** FR-021: observed totals disagree with the declared header. */
    RECONCILIATION_MISMATCH,

    /** FR-015: rejects exceeded the ratio after the floor, so reading was abandoned. */
    EARLY_STOP_THRESHOLD,

    /** FR-006a: a new file arrived for a business date already SETTLED. */
    SETTLED_DATE_CONFLICT
}

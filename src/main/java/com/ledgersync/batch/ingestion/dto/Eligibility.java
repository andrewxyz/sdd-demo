package com.ledgersync.batch.ingestion.dto;

/**
 * What to do with a complete delivery. Clarification Q4 settled that redelivery and
 * already-settled-date are two separate rules, not one; this enum keeps them separate.
 */
public enum Eligibility {

    /** New fingerprint, and the business date is open or previously FAILED (FR-007). */
    PROCESS,

    /** Fingerprint already seen — a true redelivery. Record as observed, create no run (FR-006). */
    DUPLICATE_IGNORED,

    /** New fingerprint but the business date is already SETTLED — hold for an operator (FR-006a). */
    SETTLED_DATE_CONFLICT
}

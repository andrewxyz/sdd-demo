package com.ledgersync.batch.domain;

import java.math.BigDecimal;

/**
 * The outcome of comparing what was read against what the header declared.
 *
 * <p>Carries all four figures rather than a bare boolean, because FR-022 requires the alert to
 * state them. Recomputing them at alert time would risk the alert disagreeing with the decision
 * that was actually made.
 */
public record ReconciliationResult(
        boolean reconciled,
        long declaredCount,
        long observedCount,
        BigDecimal declaredNetTotal,
        BigDecimal observedNetTotal,
        long rejectedCount,
        String failureReason) {

    public boolean countMatches() {
        return declaredCount == observedCount;
    }

    public boolean netTotalMatches() {
        return declaredNetTotal != null
                && observedNetTotal != null
                && declaredNetTotal.compareTo(observedNetTotal) == 0;
    }
}

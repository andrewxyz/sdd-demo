package com.ledgersync.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The file's own declaration of what it contains — the independent figure reconciliation is
 * checked against (FR-017).
 */
public record FileHeader(
        LocalDate businessDate,
        long declaredCount,
        BigDecimal declaredNetTotal) {
}

package com.ledgersync.batch.ingestion.dto;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * A data file whose sentinel has arrived, so it is complete and eligible to be considered
 * (FR-002). Immutable record (Constitution III).
 */
public record DeliveryCandidate(
        Path dataFile,
        Path sentinelFile,
        LocalDate businessDate,
        String contentFingerprint) {
}

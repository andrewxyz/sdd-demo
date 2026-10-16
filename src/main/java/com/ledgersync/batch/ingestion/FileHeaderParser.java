package com.ledgersync.batch.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.ledgersync.batch.domain.FileHeader;

import org.springframework.stereotype.Component;

/**
 * Parses the control header: {@code H,<business_date>,<declared_record_count>,<declared_net_total>}
 * (contracts/transaction-file.md section 3).
 *
 * <p>FR-023: a missing or unparseable header fails the run. Without it the file cannot be proven
 * correct, and settling on trust is not an option a ledger offers.
 */
@Component
public class FileHeaderParser {

    private static final String HEADER_MARKER = "H";
    private static final int EXPECTED_FIELDS = 4;

    public FileHeader parse(String headerLine) {
        if (headerLine == null || headerLine.isBlank()) {
            throw new HeaderUnreadableException("File contains no header row");
        }
        String[] fields = headerLine.split(",", -1);
        if (fields.length != EXPECTED_FIELDS) {
            throw new HeaderUnreadableException(
                    "Header has %d fields, expected %d".formatted(fields.length, EXPECTED_FIELDS));
        }
        if (!HEADER_MARKER.equals(fields[0].trim())) {
            throw new HeaderUnreadableException(
                    "First row is not a header: expected marker 'H', found '%s'"
                            .formatted(fields[0].trim()));
        }

        LocalDate businessDate;
        try {
            businessDate = LocalDate.parse(fields[1].trim());
        } catch (RuntimeException e) {
            throw new HeaderUnreadableException(
                    "Header business date '%s' is not an ISO date".formatted(fields[1].trim()));
        }

        long declaredCount;
        try {
            declaredCount = Long.parseLong(fields[2].trim());
        } catch (NumberFormatException e) {
            throw new HeaderUnreadableException(
                    "Header record count '%s' is not an integer".formatted(fields[2].trim()));
        }
        if (declaredCount < 0) {
            throw new HeaderUnreadableException(
                    "Header record count must not be negative, was " + declaredCount);
        }

        BigDecimal declaredNetTotal;
        try {
            declaredNetTotal = new BigDecimal(fields[3].trim());
        } catch (NumberFormatException e) {
            throw new HeaderUnreadableException(
                    "Header net total '%s' is not a decimal".formatted(fields[3].trim()));
        }

        return new FileHeader(businessDate, declaredCount, declaredNetTotal);
    }
}

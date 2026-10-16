package com.ledgersync.batch.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.ledgersync.batch.domain.TransactionRecord;

import org.springframework.stereotype.Component;

/**
 * Maps a {@code D} detail row to a {@link TransactionRecord}
 * (contracts/transaction-file.md section 3).
 *
 * <p>This class deliberately does <em>not</em> validate. It turns text into a record, or throws
 * because the text is not a record at all. Business validation is FR-008 to FR-012 and belongs to
 * the reconciliation layer (Constitution II).
 */
@Component
public class TransactionRecordMapper {

    private static final String DETAIL_MARKER = "D";
    private static final int EXPECTED_FIELDS = 5;

    public TransactionRecord map(String line, long rowNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != EXPECTED_FIELDS) {
            throw new MalformedRowException(
                    rowNumber, "row",
                    "Row has %d fields, expected %d".formatted(fields.length, EXPECTED_FIELDS),
                    line);
        }
        if (!DETAIL_MARKER.equals(fields[0].trim())) {
            throw new MalformedRowException(
                    rowNumber, "recordMarker",
                    "Row marker must be 'D', found '%s'".formatted(fields[0].trim()), line);
        }

        String accountNumber = fields[1].trim();
        String type = fields[3].trim().toUpperCase();

        BigDecimal amount;
        try {
            amount = new BigDecimal(fields[2].trim());
        } catch (NumberFormatException e) {
            throw new MalformedRowException(
                    rowNumber, "amount",
                    "Amount '%s' is not a number".formatted(fields[2].trim()), line);
        }

        Instant timestamp;
        try {
            timestamp = OffsetDateTime.parse(fields[4].trim()).toInstant();
        } catch (RuntimeException e) {
            throw new MalformedRowException(
                    rowNumber, "timestamp",
                    "Timestamp '%s' is not an ISO-8601 instant with offset"
                            .formatted(fields[4].trim()),
                    line);
        }

        return new TransactionRecord(rowNumber, accountNumber, type, amount, timestamp, line);
    }
}

package com.ledgersync.batch.persistence;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

import com.ledgersync.batch.ingestion.MalformedRowException;
import com.ledgersync.batch.persistence.entity.RejectedRecordEntity;
import com.ledgersync.batch.persistence.repository.RejectedRecordRepository;
import com.ledgersync.batch.reconciliation.RecordRejectedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a durable row for every skipped item (FR-013). Constitution IV: silent skipping is
 * prohibited.
 *
 * <p><strong>Why {@code REQUIRES_NEW} is not optional here.</strong> Spring Batch rolls the chunk
 * back when an item is skipped, then replays the chunk item by item. A write that joined the
 * chunk transaction would be rolled back with it, and the rejected row would vanish — while the
 * run's own {@code records_rejected} counter still said 7. The failure would look exactly like a
 * clean chunk. Constitution Principle I requires propagation to be declared wherever the default
 * is wrong, and this is the case it was written for. {@code RejectSurvivesRollbackIT} is the
 * regression test.
 */
@Component
public class RejectedRecordWriter {

    /**
     * Account-like tokens: 10 or more alphanumeric characters, leaving the last four visible.
     *
     * <p>The lower bound is 10 rather than 6 deliberately. At 6 the pattern also swallowed
     * {@code CREDIT}, so an excerpt read {@code D,,10.00,**EDIT,...} — safe, but it hid the
     * transaction type an operator needs to diagnose the row, working against SC-007. Ten is
     * comfortably above every structural token in the file format and comfortably below the
     * shortest real account identifier.
     */
    private static final Pattern ACCOUNT_LIKE = Pattern.compile("\\b[A-Za-z0-9]{10,34}\\b");

    private static final int MAX_EXCERPT = 512;
    private static final int MAX_REASON = 256;
    private static final int MAX_FIELD = 64;

    private static final Logger log = LoggerFactory.getLogger(RejectedRecordWriter.class);

    private final RejectedRecordRepository repository;
    private final Clock clock;

    public RejectedRecordWriter(RejectedRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSkip(UUID executionId, Throwable cause) {
        long rowNumber;
        String failingField;
        String rawLine;

        if (cause instanceof RecordRejectedException rejected) {
            rowNumber = rejected.getSourceRowNumber();
            failingField = rejected.getFailingField();
            rawLine = rejected.getRawLine();
        } else if (cause instanceof MalformedRowException malformed) {
            rowNumber = malformed.getSourceRowNumber();
            failingField = malformed.getFailingField();
            rawLine = malformed.getRawLine();
        } else {
            rowNumber = -1;
            failingField = "unknown";
            rawLine = null;
        }

        RejectedRecordEntity entity = new RejectedRecordEntity(
                executionId,
                rowNumber,
                truncate(failingField, MAX_FIELD),
                truncate(reasonOf(cause), MAX_REASON),
                mask(rawLine),
                clock.instant());
        repository.save(entity);

        // Deliberately logs the row number and reason, never the row content.
        log.warn("Rejected row {} ({}): {}", rowNumber, failingField, reasonOf(cause));
    }

    /**
     * FR-031: masking happens here, at write time, so the plaintext never reaches storage. Masking
     * on read would leave the clear value in the database for anyone with query access.
     */
    public static String mask(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String masked = ACCOUNT_LIKE.matcher(rawLine).replaceAll(match -> {
            String value = match.group();
            String tail = value.substring(value.length() - 4);
            return "*".repeat(value.length() - 4) + tail;
        });
        return truncate(masked, MAX_EXCERPT);
    }

    private static String reasonOf(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "Unspecified validation failure" : message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

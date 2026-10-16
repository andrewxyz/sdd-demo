package com.ledgersync.batch.config;

import java.util.UUID;

import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.persistence.RejectedRecordWriter;

import org.springframework.batch.core.SkipListener;

/**
 * Writes a durable rejected-record row for every skipped item (FR-013).
 *
 * <p>All three callbacks route to the same writer, because a defect caught while reading, while
 * processing, or while writing is equally a defect the operator needs to see. Missing one of
 * them is how a rejection list silently becomes incomplete.
 */
public class RejectedRecordSkipListener implements SkipListener<TransactionRecord, TransactionRecord> {

    private final RejectedRecordWriter writer;
    private final UUID executionId;

    public RejectedRecordSkipListener(RejectedRecordWriter writer, UUID executionId) {
        this.writer = writer;
        this.executionId = executionId;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        writer.recordSkip(executionId, t);
    }

    @Override
    public void onSkipInProcess(TransactionRecord item, Throwable t) {
        writer.recordSkip(executionId, t);
    }

    @Override
    public void onSkipInWrite(TransactionRecord item, Throwable t) {
        writer.recordSkip(executionId, t);
    }
}

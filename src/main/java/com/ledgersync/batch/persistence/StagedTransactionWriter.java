package com.ledgersync.batch.persistence;

import java.util.List;
import java.util.UUID;

import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.persistence.entity.StagedTransaction;
import com.ledgersync.batch.persistence.repository.StagedTransactionRepository;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists validated rows to staging (research R2). Nothing here touches a customer balance.
 *
 * <p>Constitution I: an explicit {@code @Transactional} boundary with declared propagation.
 * {@code REQUIRED} is correct — this write belongs to the chunk transaction and must roll back
 * with it.
 */
public class StagedTransactionWriter implements ItemWriter<TransactionRecord> {

    private final StagedTransactionRepository repository;
    private final UUID executionId;

    public StagedTransactionWriter(StagedTransactionRepository repository, UUID executionId) {
        this.repository = repository;
        this.executionId = executionId;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void write(Chunk<? extends TransactionRecord> chunk) {
        List<StagedTransaction> staged = chunk.getItems().stream()
                .map(this::toEntity)
                .toList();
        repository.saveAll(staged);
    }

    private StagedTransaction toEntity(TransactionRecord record) {
        return new StagedTransaction(
                executionId,
                record.sourceRowNumber(),
                record.accountNumber(),
                record.type(),
                record.amount(),
                // Direction applied once, here, so reconciliation is a plain SUM().
                record.signedAmount(),
                record.timestamp());
    }
}

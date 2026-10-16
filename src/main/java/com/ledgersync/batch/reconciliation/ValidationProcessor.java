package com.ledgersync.batch.reconciliation;

import java.time.LocalDate;

import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.domain.ValidationOutcome;

import org.springframework.batch.item.ItemProcessor;

/**
 * Thin adapter wrapping {@link RecordValidator} as a Spring Batch {@link ItemProcessor}.
 *
 * <p>The rules live in {@code RecordValidator}, which knows nothing of Spring Batch. This class
 * exists only to translate a rejection into the exception the step's skip policy acts on, so that
 * the framework boundary stays one class thick (Constitution II).
 */
public class ValidationProcessor implements ItemProcessor<TransactionRecord, TransactionRecord> {

    private final RecordValidator validator;
    private final LocalDate businessDate;

    public ValidationProcessor(RecordValidator validator, LocalDate businessDate) {
        this.validator = validator;
        this.businessDate = businessDate;
    }

    @Override
    public TransactionRecord process(TransactionRecord item) {
        ValidationOutcome outcome = validator.validate(item, businessDate);
        if (!outcome.valid()) {
            throw new RecordRejectedException(
                    item.sourceRowNumber(), outcome.failingField(), outcome.reason(),
                    item.rawLine());
        }
        return item;
    }
}

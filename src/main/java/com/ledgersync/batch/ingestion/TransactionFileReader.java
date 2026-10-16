package com.ledgersync.batch.ingestion;

import java.nio.file.Path;

import com.ledgersync.batch.domain.TransactionRecord;

import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.stereotype.Component;

/**
 * Builds the chunk-oriented reader over the decrypted file.
 *
 * <p>{@link FlatFileItemReader} is used rather than a hand-rolled loop because it records its
 * position in the step's execution context. That is what makes restart resume at the last
 * committed chunk instead of re-reading 500,000 rows (research R6) — and Constitution II forbids
 * reimplementing what the framework already solves.
 */
@Component
public class TransactionFileReader {

    private final TransactionRecordMapper mapper;

    public TransactionFileReader(TransactionRecordMapper mapper) {
        this.mapper = mapper;
    }

    public FlatFileItemReader<TransactionRecord> build(Path plaintextFile) {
        return new FlatFileItemReaderBuilder<TransactionRecord>()
                .name("transactionFileReader")
                .resource(new org.springframework.core.io.FileSystemResource(plaintextFile))
                // The control header is consumed separately by FileHeaderParser (FR-017).
                .linesToSkip(1)
                .lineMapper((line, lineNumber) -> mapper.map(line, lineNumber))
                .strict(true)
                .build();
    }
}

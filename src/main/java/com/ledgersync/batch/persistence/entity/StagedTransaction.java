package com.ledgersync.batch.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row read from the file, held pending reconciliation (research R2).
 *
 * <p>Constitution III — mutable class, not a record.
 */
@Entity
@Table(name = "settlement_staged_transaction")
public class StagedTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "source_row_number", nullable = false)
    private long sourceRowNumber;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "transaction_type", nullable = false, length = 16)
    private String transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Amount with direction applied, so reconciliation is a plain SUM(). */
    @Column(name = "signed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal signedAmount;

    @Column(name = "transaction_timestamp", nullable = false)
    private Instant transactionTimestamp;

    protected StagedTransaction() {
        // JPA
    }

    public StagedTransaction(
            UUID executionId, long sourceRowNumber, String accountNumber, String transactionType,
            BigDecimal amount, BigDecimal signedAmount, Instant transactionTimestamp) {
        this.executionId = executionId;
        this.sourceRowNumber = sourceRowNumber;
        this.accountNumber = accountNumber;
        this.transactionType = transactionType;
        this.amount = amount;
        this.signedAmount = signedAmount;
        this.transactionTimestamp = transactionTimestamp;
    }

    public Long getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public long getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getSignedAmount() {
        return signedAmount;
    }

    public Instant getTransactionTimestamp() {
        return transactionTimestamp;
    }
}

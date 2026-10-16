package com.ledgersync.batch.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Owned by an existing system of record. This feature updates {@code balance} and
 * {@code lastSettledAt}; it does not create or delete accounts (spec Assumptions).
 *
 * <p>The business key {@code account_number} is the identifier used across all layers — there is
 * no surrogate id to leak (FR-030).
 */
@Entity
@Table(name = "customer_balance")
public class CustomerBalance {

    @Id
    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "last_settled_at")
    private Instant lastSettledAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    protected CustomerBalance() {
        // JPA
    }

    public CustomerBalance(String accountNumber, BigDecimal balance, String status) {
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.status = status;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Instant getLastSettledAt() {
        return lastSettledAt;
    }

    public void setLastSettledAt(Instant lastSettledAt) {
        this.lastSettledAt = lastSettledAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

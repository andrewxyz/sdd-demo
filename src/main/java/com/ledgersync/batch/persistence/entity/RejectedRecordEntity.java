package com.ledgersync.batch.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row that failed validation, kept with its reason (FR-013).
 *
 * <p>{@code rawRowExcerpt} is the one field here that could leak customer data, so it is masked
 * before it ever reaches this object (FR-031).
 */
@Entity
@Table(name = "settlement_rejected_record")
public class RejectedRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "source_row_number", nullable = false)
    private long sourceRowNumber;

    @Column(name = "failing_field", nullable = false, length = 64)
    private String failingField;

    @Column(name = "rejection_reason", nullable = false, length = 256)
    private String rejectionReason;

    @Column(name = "raw_row_excerpt", length = 512)
    private String rawRowExcerpt;

    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    protected RejectedRecordEntity() {
        // JPA
    }

    public RejectedRecordEntity(
            UUID executionId, long sourceRowNumber, String failingField, String rejectionReason,
            String rawRowExcerpt, Instant rejectedAt) {
        this.executionId = executionId;
        this.sourceRowNumber = sourceRowNumber;
        this.failingField = failingField;
        this.rejectionReason = rejectionReason;
        this.rawRowExcerpt = rawRowExcerpt;
        this.rejectedAt = rejectedAt;
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

    public String getFailingField() {
        return failingField;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getRawRowExcerpt() {
        return rawRowExcerpt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }
}

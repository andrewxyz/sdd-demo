package com.ledgersync.batch.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One execution against one file: the unit of audit.
 *
 * <p>Constitution III — this is a mutable class, not a record. Records are never JPA entities.
 *
 * <p>Constitution I / FR-030 — {@code id} is internal. Nothing outside the persistence layer ever
 * sees it; external correlation uses {@link #executionId}.
 */
@Entity
@Table(name = "settlement_run")
public class SettlementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, unique = true, updatable = false)
    private UUID executionId;

    @Column(name = "source_file_name", nullable = false, length = 512)
    private String sourceFileName;

    @Column(name = "content_fingerprint", nullable = false, unique = true, length = 64)
    private String contentFingerprint;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "records_read", nullable = false)
    private long recordsRead;

    @Column(name = "records_rejected", nullable = false)
    private long recordsRejected;

    @Column(name = "records_applied", nullable = false)
    private long recordsApplied;

    @Column(name = "declared_count")
    private Long declaredCount;

    @Column(name = "declared_net_total", precision = 19, scale = 4)
    private BigDecimal declaredNetTotal;

    @Column(name = "observed_count")
    private Long observedCount;

    @Column(name = "observed_net_total", precision = 19, scale = 4)
    private BigDecimal observedNetTotal;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Column(name = "early_stopped", nullable = false)
    private boolean earlyStopped;

    protected SettlementRun() {
        // JPA
    }

    public SettlementRun(
            UUID executionId, String sourceFileName, String contentFingerprint,
            LocalDate businessDate, Instant startedAt) {
        this.executionId = executionId;
        this.sourceFileName = sourceFileName;
        this.contentFingerprint = contentFingerprint;
        this.businessDate = businessDate;
        this.startedAt = startedAt;
        this.status = RunStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getRecordsRead() {
        return recordsRead;
    }

    public void setRecordsRead(long recordsRead) {
        this.recordsRead = recordsRead;
    }

    public long getRecordsRejected() {
        return recordsRejected;
    }

    public void setRecordsRejected(long recordsRejected) {
        this.recordsRejected = recordsRejected;
    }

    public long getRecordsApplied() {
        return recordsApplied;
    }

    public void setRecordsApplied(long recordsApplied) {
        this.recordsApplied = recordsApplied;
    }

    public Long getDeclaredCount() {
        return declaredCount;
    }

    public void setDeclaredCount(Long declaredCount) {
        this.declaredCount = declaredCount;
    }

    public BigDecimal getDeclaredNetTotal() {
        return declaredNetTotal;
    }

    public void setDeclaredNetTotal(BigDecimal declaredNetTotal) {
        this.declaredNetTotal = declaredNetTotal;
    }

    public Long getObservedCount() {
        return observedCount;
    }

    public void setObservedCount(Long observedCount) {
        this.observedCount = observedCount;
    }

    public BigDecimal getObservedNetTotal() {
        return observedNetTotal;
    }

    public void setObservedNetTotal(BigDecimal observedNetTotal) {
        this.observedNetTotal = observedNetTotal;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public boolean isEarlyStopped() {
        return earlyStopped;
    }

    public void setEarlyStopped(boolean earlyStopped) {
        this.earlyStopped = earlyStopped;
    }
}

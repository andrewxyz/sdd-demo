package com.ledgersync.batch.persistence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.ledgersync.batch.domain.ReconciliationResult;
import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.entity.SettlementRun;
import com.ledgersync.batch.persistence.repository.SettlementRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the run's lifecycle and counts (FR-004, FR-005, FR-026, FR-029).
 *
 * <p>Every method declares an explicit transaction boundary (Constitution I). Terminal
 * transitions use {@code REQUIRES_NEW} so a run can be recorded as FAILED even when the
 * surrounding chunk transaction is being rolled back — otherwise the evidence of the failure
 * would roll back along with it.
 */
@Service
public class SettlementRunService {

    private final SettlementRunRepository repository;
    private final Clock clock;

    public SettlementRunService(SettlementRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementRun start(
            UUID executionId, String sourceFileName, String fingerprint, LocalDate businessDate) {
        SettlementRun run = new SettlementRun(
                executionId, sourceFileName, fingerprint, businessDate, clock.instant());
        run.setStatus(RunStatus.RUNNING);
        return repository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordHeader(UUID executionId, long declaredCount, java.math.BigDecimal declaredNetTotal) {
        SettlementRun run = require(executionId);
        run.setDeclaredCount(declaredCount);
        run.setDeclaredNetTotal(declaredNetTotal);
        repository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCounts(UUID executionId, long read, long rejected, boolean earlyStopped) {
        SettlementRun run = require(executionId);
        run.setRecordsRead(read);
        run.setRecordsRejected(rejected);
        run.setEarlyStopped(earlyStopped);
        repository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordReconciliation(UUID executionId, ReconciliationResult result) {
        SettlementRun run = require(executionId);
        run.setObservedCount(result.observedCount());
        run.setObservedNetTotal(result.observedNetTotal());
        run.setDeclaredCount(result.declaredCount());
        run.setDeclaredNetTotal(result.declaredNetTotal());
        repository.save(run);
    }

    /**
     * FR-026: a run reaches SETTLED only after balances were actually applied.
     *
     * <p>{@code REQUIRED} on purpose — this must commit in the same transaction as the balance
     * update in {@code BalanceApplier}, so the ledger and the run record can never disagree.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markSettled(UUID executionId, long recordsApplied) {
        SettlementRun run = require(executionId);
        run.setRecordsApplied(recordsApplied);
        run.setCompletedAt(clock.instant());
        run.setStatus(RunStatus.SETTLED);
        repository.save(run);
    }

    /**
     * {@code REQUIRES_NEW} on purpose, and the opposite of {@link #markSettled}. The reconcile
     * tasklet marks the run failed and then throws, which rolls the step transaction back — so
     * this write must be independent or the evidence of the failure would roll back with it.
     */
    public void markFailed(UUID executionId, String reason) {
        markFailed(executionId, reason, null);
    }

    /**
     * Marks the run FAILED and persists the observed figures alongside it.
     *
     * <p>The figures have to be written here rather than relied upon from the reconcile step:
     * that step's transaction rolls back when it throws, taking {@code observed_count} and
     * {@code observed_net_total} with it. FR-022 requires the alert to state them, so they are
     * carried out of the job and written once, after the rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID executionId, String reason, ReconciliationResult figures) {
        SettlementRun run = require(executionId);
        if (figures != null) {
            run.setObservedCount(figures.observedCount());
            run.setObservedNetTotal(figures.observedNetTotal());
            run.setDeclaredCount(figures.declaredCount());
            run.setDeclaredNetTotal(figures.declaredNetTotal());
            run.setRecordsRejected(figures.rejectedCount());
            run.setRecordsRead(figures.observedCount() + figures.rejectedCount());
        }
        if (run.getStatus() != null && run.getStatus().isTerminal()) {
            // Idempotent: the job listener and the orchestrator can both reach this for the same
            // run, and the first reason recorded is the one closest to the cause. Figures above
            // are still saved, since they are the same either way.
            repository.save(run);
            return;
        }
        run.setFailureReason(truncate(reason));
        run.setCompletedAt(clock.instant());
        run.setStatus(RunStatus.FAILED);
        repository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public SettlementRun require(UUID executionId) {
        return repository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No settlement run for execution " + executionId));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 256 ? reason : reason.substring(0, 256);
    }
}

package com.ledgersync.batch.persistence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.entity.SettlementRun;
import com.ledgersync.batch.persistence.repository.SettlementRunRepository;
import com.ledgersync.batch.persistence.repository.StagedTransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-016 / FR-029 retention.
 *
 * <p>Two different clocks apply, which is why this is not one sweep. Staged rows are working
 * data: they are the bulk of the volume and carry no audit value once a run is terminal, so they
 * go early. Runs and their rejected records are the audit trail and follow the organisation's
 * financial record retention standard.
 */
@Service
public class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final SettlementRunRepository runRepository;
    private final StagedTransactionRepository stagedRepository;
    private final Clock clock;
    private final int stagedRetentionDays;
    private final int auditRetentionDays;

    public RetentionPurgeService(
            SettlementRunRepository runRepository,
            StagedTransactionRepository stagedRepository,
            Clock clock,
            @Value("${ledgersync.retention.staged-days:30}") int stagedRetentionDays,
            @Value("${ledgersync.retention.audit-days:2555}") int auditRetentionDays) {
        this.runRepository = runRepository;
        this.stagedRepository = stagedRepository;
        this.clock = clock;
        this.stagedRetentionDays = stagedRetentionDays;
        this.auditRetentionDays = auditRetentionDays;
    }

    @Scheduled(cron = "${ledgersync.retention.cron:0 30 4 * * *}")
    public void purge() {
        purgeStagedRows();
        purgeAgedRuns();
    }

    /** Working data for terminal runs: the volume, with no audit value. */
    @Transactional(propagation = Propagation.REQUIRED)
    public int purgeStagedRows() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(stagedRetentionDays);
        int removed = 0;
        for (SettlementRun run : runRepository.findByBusinessDateBefore(cutoff)) {
            if (run.getStatus() != null && run.getStatus().isTerminal()) {
                removed += stagedRepository.deleteByExecutionId(run.getExecutionId());
            }
        }
        if (removed > 0) {
            log.info("Purged {} staged rows older than {}", removed, cutoff);
        }
        return removed;
    }

    /**
     * Runs and, by cascade, their rejected records. Only terminal runs are eligible — an
     * in-flight run is never purged regardless of its business date.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int purgeAgedRuns() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(auditRetentionDays);
        List<SettlementRun> aged = runRepository.findByBusinessDateBefore(cutoff).stream()
                .filter(r -> r.getStatus() == RunStatus.SETTLED || r.getStatus() == RunStatus.FAILED)
                .toList();
        runRepository.deleteAll(aged);
        if (!aged.isEmpty()) {
            log.info("Purged {} settlement runs older than {}", aged.size(), cutoff);
        }
        return aged.size();
    }
}

package com.ledgersync.batch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.ledgersync.batch.alerting.AlertDispatcher;
import com.ledgersync.batch.alerting.dto.AlertType;
import com.ledgersync.batch.config.SettlementJobConfig;
import com.ledgersync.batch.config.SettlementProperties;
import com.ledgersync.batch.config.SettlementRunLock;
import com.ledgersync.batch.domain.FileHeader;
import com.ledgersync.batch.ingestion.BrokenDeliveryException;
import com.ledgersync.batch.ingestion.DecryptionFailedException;
import com.ledgersync.batch.ingestion.FileArchiver;
import com.ledgersync.batch.ingestion.FileDecryptor;
import com.ledgersync.batch.ingestion.FileHeaderParser;
import com.ledgersync.batch.ingestion.HeaderUnreadableException;
import com.ledgersync.batch.ingestion.LandingZoneScanner;
import com.ledgersync.batch.ingestion.dto.DeliveryCandidate;
import com.ledgersync.batch.ingestion.dto.Eligibility;
import com.ledgersync.batch.persistence.SettlementRunService;
import com.ledgersync.batch.persistence.entity.SettlementRun;
import com.ledgersync.batch.persistence.repository.StagedTransactionRepository;
import com.ledgersync.batch.reconciliation.ReconciliationFailedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Drives one settlement cycle: scan, decide, run, alert, archive.
 *
 * <p>Two ordering decisions here are load-bearing:
 *
 * <ul>
 *   <li><strong>Alerts are dispatched after the run's outcome is committed</strong>, never inside
 *       the transaction (research R11). An alert published inside a transaction that then rolls
 *       back would be discarded with it — the run fails and nobody is told.
 *   <li><strong>The decrypted file is deleted in a {@code finally} block</strong> (research R10).
 *       Plaintext financial data at rest is the sharp edge, and the happy path is exactly the one
 *       you cannot rely on.
 * </ul>
 */
@Service
public class SettlementOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SettlementOrchestrator.class);

    private final SettlementProperties properties;
    private final SettlementRunLock runLock;
    private final LandingZoneScanner scanner;
    private final FileDecryptor decryptor;
    private final FileHeaderParser headerParser;
    private final FileArchiver archiver;
    private final SettlementRunService runService;
    private final StagedTransactionRepository stagedRepository;
    private final AlertDispatcher alertDispatcher;
    private final JobLauncher jobLauncher;
    private final Job settlementJob;

    public SettlementOrchestrator(
            SettlementProperties properties,
            SettlementRunLock runLock,
            LandingZoneScanner scanner,
            FileDecryptor decryptor,
            FileHeaderParser headerParser,
            FileArchiver archiver,
            SettlementRunService runService,
            StagedTransactionRepository stagedRepository,
            AlertDispatcher alertDispatcher,
            JobLauncher jobLauncher,
            @Qualifier("endOfDaySettlementJob") Job settlementJob) {
        this.properties = properties;
        this.runLock = runLock;
        this.scanner = scanner;
        this.decryptor = decryptor;
        this.headerParser = headerParser;
        this.archiver = archiver;
        this.runService = runService;
        this.stagedRepository = stagedRepository;
        this.alertDispatcher = alertDispatcher;
        this.jobLauncher = jobLauncher;
        this.settlementJob = settlementJob;
    }

    /**
     * Processes every complete delivery, oldest business date first, one at a time (FR-025a,
     * FR-025c). Returns the number of files that produced a settlement run.
     */
    public int runOnce() throws Exception {
        Integer processed = runLock.withLock(() -> {
            List<DeliveryCandidate> candidates;
            try {
                candidates = scanner.scan();
            } catch (BrokenDeliveryException e) {
                alertDispatcher.simple(
                        AlertType.BROKEN_DELIVERY, UUID.randomUUID(), e.getDataFileName(),
                        null, "FAILED", e.getMessage());
                return 0;
            }

            int count = 0;
            for (DeliveryCandidate candidate : candidates) {
                if (process(candidate)) {
                    count++;
                }
            }
            return count;
        });
        return processed == null ? 0 : processed;
    }

    private boolean process(DeliveryCandidate candidate) {
        Eligibility eligibility = scanner.assess(candidate);
        switch (eligibility) {
            case DUPLICATE_IGNORED -> {
                // FR-006: observed, but no new run. Archived so the zone does not grow.
                log.info("Ignoring redelivery of an already-processed file");
                archiver.archive(candidate);
                return false;
            }
            case SETTLED_DATE_CONFLICT -> {
                // FR-006a: left in place deliberately — an operator needs to look at it.
                alertDispatcher.simple(
                        AlertType.SETTLED_DATE_CONFLICT, UUID.randomUUID(),
                        candidate.dataFile().getFileName().toString(), candidate.businessDate(),
                        "PENDING",
                        "A new file arrived for business date %s, which is already SETTLED"
                                .formatted(candidate.businessDate()));
                return false;
            }
            default -> {
                return settle(candidate);
            }
        }
    }

    private boolean settle(DeliveryCandidate candidate) {
        UUID executionId = UUID.randomUUID();
        String fileName = candidate.dataFile().getFileName().toString();
        runService.start(executionId, fileName, candidate.contentFingerprint(),
                candidate.businessDate());

        Path plaintext = null;
        try {
            plaintext = decryptor.decryptToTempFile(
                    candidate.dataFile(),
                    properties.privateKeyPath(),
                    passphrase());

            FileHeader header = headerParser.parse(firstLineOf(plaintext));
            runService.recordHeader(executionId, header.declaredCount(), header.declaredNetTotal());

            // FR-028 / research R6: clear any staged rows from a previous attempt before
            // re-staging, so a resumed run cannot double-count.
            stagedRepository.deleteByExecutionId(executionId);

            // jobLauncher.run returns normally when the job FAILS — it throws only for launch
            // problems such as a duplicate instance. Relying on an exception here meant a
            // reconciliation mismatch produced no alert at all: the run was correctly marked
            // FAILED and nobody was told, which is exactly the silence FR-021 exists to prevent.
            JobExecution execution =
                    jobLauncher.run(settlementJob, jobParameters(executionId, plaintext, candidate));

            if (execution.getStatus() != BatchStatus.COMPLETED) {
                handleJobFailure(executionId, fileName, candidate, execution);
                archiver.archive(candidate);
                return false;
            }
            archiver.archive(candidate);
            return true;

        } catch (DecryptionFailedException e) {
            failAndAlert(executionId, fileName, candidate, AlertType.DECRYPTION_FAILED, e.getMessage());
            archiver.archive(candidate);
            return false;
        } catch (HeaderUnreadableException e) {
            failAndAlert(executionId, fileName, candidate, AlertType.HEADER_UNREADABLE, e.getMessage());
            archiver.archive(candidate);
            return false;
        } catch (Exception e) {
            handleJobFailure(executionId, fileName, candidate, e);
            archiver.archive(candidate);
            return false;
        } finally {
            // Unconditional: plaintext must not survive the run, whatever happened.
            decryptor.deleteQuietly(plaintext);
        }
    }

    private void handleJobFailure(
            UUID executionId, String fileName, DeliveryCandidate candidate,
            JobExecution execution) {
        ReconciliationFailedException mismatch = execution.getAllFailureExceptions().stream()
                .map(SettlementOrchestrator::unwrapReconciliation)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        handleJobFailure(executionId, fileName, candidate, mismatch,
                execution.getAllFailureExceptions().stream()
                        .map(Throwable::getMessage)
                        .filter(m -> m != null && !m.isBlank())
                        .findFirst()
                        .orElse("Settlement job did not complete"));
    }

    private void handleJobFailure(
            UUID executionId, String fileName, DeliveryCandidate candidate, Exception e) {
        handleJobFailure(executionId, fileName, candidate, unwrapReconciliation(e),
                e.getMessage());
    }

    private void handleJobFailure(
            UUID executionId, String fileName, DeliveryCandidate candidate,
            ReconciliationFailedException mismatch, String fallbackReason) {
        SettlementRun run = runService.require(executionId);

        if (mismatch != null) {
            // The run was marked FAILED by SettlementOutcomeListener.afterJob; alerting happens
            // here, once the transaction has settled (research R11).
            if (run.isEarlyStopped()) {
                alertDispatcher.earlyStop(
                        executionId, fileName, candidate.businessDate(),
                        mismatch.getResult().rejectedCount(), run.getRecordsRead());
            } else {
                alertDispatcher.reconciliationMismatch(
                        executionId, fileName, candidate.businessDate(),
                        mismatch.getResult(), run.getRecordsRead(), run.isEarlyStopped());
            }
            return;
        }

        log.error("Settlement run failed: {}", fallbackReason);
        runService.markFailed(executionId, fallbackReason);
        alertDispatcher.simple(
                AlertType.RECONCILIATION_MISMATCH, executionId, fileName, candidate.businessDate(),
                "FAILED", "Settlement run failed: " + fallbackReason);
    }

    private void failAndAlert(
            UUID executionId, String fileName, DeliveryCandidate candidate, AlertType type,
            String reason) {
        runService.markFailed(executionId, reason);
        alertDispatcher.simple(type, executionId, fileName, candidate.businessDate(), "FAILED",
                reason);
    }

    private static ReconciliationFailedException unwrapReconciliation(Throwable t) {
        while (t != null) {
            if (t instanceof ReconciliationFailedException r) {
                return r;
            }
            t = t.getCause();
        }
        return null;
    }

    private JobParameters jobParameters(
            UUID executionId, Path plaintext, DeliveryCandidate candidate) {
        return new JobParametersBuilder()
                .addString(SettlementJobConfig.PARAM_EXECUTION_ID, executionId.toString())
                .addString(SettlementJobConfig.PARAM_PLAINTEXT_PATH, plaintext.toString())
                .addString(SettlementJobConfig.PARAM_BUSINESS_DATE,
                        candidate.businessDate().toString())
                .toJobParameters();
    }

    private char[] passphrase() {
        String configured = properties.privateKeyPassphrase();
        return configured == null ? new char[0] : configured.toCharArray();
    }

    private static String firstLineOf(Path file) {
        try (var lines = Files.lines(file)) {
            return lines.findFirst().orElse(null);
        } catch (Exception e) {
            throw new HeaderUnreadableException("Could not read the header row: " + e.getMessage());
        }
    }
}

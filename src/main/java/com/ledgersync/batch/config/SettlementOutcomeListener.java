package com.ledgersync.batch.config;

import java.math.BigDecimal;
import java.util.UUID;

import com.ledgersync.batch.domain.ReconciliationResult;
import com.ledgersync.batch.persistence.SettlementRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Marks a run FAILED once the job has finished.
 *
 * <p><strong>Why this is a listener and not a call inside the tasklet.</strong> The reconcile
 * tasklet's transaction has already updated the {@code settlement_run} row by the time it decides
 * the file does not balance. Calling a {@code REQUIRES_NEW} {@code markFailed} from there
 * suspends that transaction and then waits for a row lock the suspended transaction still holds —
 * a self-deadlock that hangs the run forever rather than failing it. Observed directly:
 * {@code pg_stat_activity} showed the inner {@code UPDATE settlement_run} waiting on
 * {@code Lock/transactionid} behind an {@code idle in transaction} session.
 *
 * <p>{@code afterJob} runs after the step transaction has rolled back, so the row is free and the
 * write succeeds. It also covers every failure mode, not just reconciliation — an unexpected
 * exception anywhere in the job still leaves the run correctly marked rather than stuck RUNNING.
 */
@Component
public class SettlementOutcomeListener implements JobExecutionListener {

    /** Key under which the reconcile step records why it failed. */
    public static final String FAILURE_REASON_KEY = "settlement.failureReason";

    private static final String DECLARED_COUNT = "settlement.declaredCount";
    private static final String OBSERVED_COUNT = "settlement.observedCount";
    private static final String DECLARED_TOTAL = "settlement.declaredNetTotal";
    private static final String OBSERVED_TOTAL = "settlement.observedNetTotal";
    private static final String REJECTED_COUNT = "settlement.rejectedCount";

    /**
     * Carries the reconciliation figures out of the step, which is about to roll back and take
     * them with it, so the post-job write can persist them for the alert (FR-022).
     */
    public static void carryFigures(ExecutionContext context, ReconciliationResult result) {
        context.putString(FAILURE_REASON_KEY, result.failureReason());
        context.putLong(DECLARED_COUNT, result.declaredCount());
        context.putLong(OBSERVED_COUNT, result.observedCount());
        context.putLong(REJECTED_COUNT, result.rejectedCount());
        context.putString(DECLARED_TOTAL, String.valueOf(result.declaredNetTotal()));
        context.putString(OBSERVED_TOTAL, String.valueOf(result.observedNetTotal()));
    }

    private static ReconciliationResult figuresFrom(ExecutionContext context) {
        if (!context.containsKey(OBSERVED_COUNT)) {
            return null;
        }
        return new ReconciliationResult(
                false,
                context.getLong(DECLARED_COUNT),
                context.getLong(OBSERVED_COUNT),
                new BigDecimal(context.getString(DECLARED_TOTAL, "0")),
                new BigDecimal(context.getString(OBSERVED_TOTAL, "0")),
                context.getLong(REJECTED_COUNT),
                context.getString(FAILURE_REASON_KEY, null));
    }

    private static final Logger log = LoggerFactory.getLogger(SettlementOutcomeListener.class);

    private final SettlementRunService runService;

    public SettlementOutcomeListener(SettlementRunService runService) {
        this.runService = runService;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            return;
        }
        String rawExecutionId =
                jobExecution.getJobParameters().getString(SettlementJobConfig.PARAM_EXECUTION_ID);
        if (rawExecutionId == null) {
            return;
        }

        String reason = jobExecution.getExecutionContext()
                .getString(FAILURE_REASON_KEY, firstFailureMessage(jobExecution));
        try {
            runService.markFailed(UUID.fromString(rawExecutionId), reason,
                    figuresFrom(jobExecution.getExecutionContext()));
        } catch (RuntimeException e) {
            // Never throw from afterJob: the run has already failed, and masking that with a
            // bookkeeping error would lose the original reason.
            log.error("Could not mark run {} as FAILED", rawExecutionId, e);
        }
    }

    private static String firstFailureMessage(JobExecution jobExecution) {
        return jobExecution.getAllFailureExceptions().stream()
                .map(Throwable::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("Settlement run failed without a stated reason");
    }
}

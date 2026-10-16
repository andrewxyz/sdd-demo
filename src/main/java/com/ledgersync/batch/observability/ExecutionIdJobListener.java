package com.ledgersync.batch.observability;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Constitution V: every run gets a unique Execution ID, bound to MDC so that all log events a run
 * emits are correlatable by one identifier, and cleared on completion to prevent context bleed.
 *
 * <p>FR-030 — the identifier is a generated UUID, deliberately <em>not</em> Spring Batch's own
 * {@code jobExecutionId}. That value is a database sequence, and using it as the public
 * correlation key would expose an internal key.
 */
@Component
public class ExecutionIdJobListener implements JobExecutionListener {

    public static final String MDC_EXECUTION_ID = "executionId";
    public static final String EXECUTION_ID_KEY = "settlement.executionId";

    private static final Logger log = LoggerFactory.getLogger(ExecutionIdJobListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        UUID executionId = UUID.randomUUID();
        jobExecution.getExecutionContext().putString(EXECUTION_ID_KEY, executionId.toString());
        MDC.put(MDC_EXECUTION_ID, executionId.toString());
        log.info("Settlement run started");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            log.info("Settlement run finished with status {}", jobExecution.getStatus());
        } finally {
            // Cleared unconditionally: a leaked MDC entry would mislabel the next run's logs.
            MDC.remove(MDC_EXECUTION_ID);
        }
    }

    /** Reads the execution id a running job bound at start. */
    public static UUID currentExecutionId(JobExecution jobExecution) {
        String raw = jobExecution.getExecutionContext().getString(EXECUTION_ID_KEY, null);
        return raw == null ? null : UUID.fromString(raw);
    }
}

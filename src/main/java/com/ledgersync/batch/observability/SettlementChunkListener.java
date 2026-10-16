package com.ledgersync.batch.observability;

import org.slf4j.MDC;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

/**
 * Re-binds the execution id to MDC before each chunk and clears it after.
 *
 * <p>Constitution V requires propagation through every layer <em>including chunk-scoped
 * threads</em>. MDC is thread-local, so a job-level listener alone leaves pooled worker threads
 * without the context — the correlation would look present in tests and be missing in production.
 * This listener is what makes the guarantee real rather than nominal (research R9).
 */
@Component
public class SettlementChunkListener implements ChunkListener {

    @Override
    public void beforeChunk(ChunkContext context) {
        bind(context);
    }

    @Override
    public void afterChunk(ChunkContext context) {
        MDC.remove(ExecutionIdJobListener.MDC_EXECUTION_ID);
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        // A chunk error still ends this thread's use of the context.
        MDC.remove(ExecutionIdJobListener.MDC_EXECUTION_ID);
    }

    private void bind(ChunkContext context) {
        String executionId = context.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .getString(ExecutionIdJobListener.EXECUTION_ID_KEY, null);
        if (executionId != null) {
            MDC.put(ExecutionIdJobListener.MDC_EXECUTION_ID, executionId);
        }
    }
}

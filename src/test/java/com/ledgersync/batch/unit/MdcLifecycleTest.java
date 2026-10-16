package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgersync.batch.observability.ExecutionIdJobListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;

/**
 * Constitution V: the Execution ID is bound to MDC for the life of a run and cleared on
 * completion, so context cannot bleed between executions on a pooled thread.
 */
class MdcLifecycleTest {

    private final ExecutionIdJobListener listener = new ExecutionIdJobListener();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("binds a generated execution id before the job runs")
    void binds_execution_id_on_start() {
        JobExecution execution = jobExecution();

        listener.beforeJob(execution);

        String bound = MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID);
        assertThat(bound).isNotBlank();
        assertThat(ExecutionIdJobListener.currentExecutionId(execution))
                .hasToString(bound);
    }

    @Test
    @DisplayName("FR-030: the correlation id is a generated UUID, not the JobRepository sequence")
    void does_not_expose_the_internal_sequence_value() {
        JobExecution execution = jobExecution();
        execution.setId(4242L);

        listener.beforeJob(execution);

        assertThat(MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID))
                .isNotEqualTo("4242")
                .doesNotContain("4242");
    }

    @Test
    @DisplayName("clears MDC after the job, so the next run cannot inherit the context")
    void clears_mdc_on_completion() {
        JobExecution execution = jobExecution();
        listener.beforeJob(execution);

        listener.afterJob(execution);

        assertThat(MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID)).isNull();
    }

    @Test
    @DisplayName("clears MDC even when the run failed")
    void clears_mdc_after_a_failed_run() {
        JobExecution execution = jobExecution();
        listener.beforeJob(execution);
        execution.setStatus(BatchStatus.FAILED);

        listener.afterJob(execution);

        assertThat(MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID)).isNull();
    }

    @Test
    @DisplayName("two consecutive runs get different ids")
    void each_run_gets_its_own_id() {
        JobExecution first = jobExecution();
        listener.beforeJob(first);
        String firstId = MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID);
        listener.afterJob(first);

        JobExecution second = jobExecution();
        listener.beforeJob(second);
        String secondId = MDC.get(ExecutionIdJobListener.MDC_EXECUTION_ID);

        assertThat(secondId).isNotEqualTo(firstId);
    }

    private static JobExecution jobExecution() {
        return new JobExecution(
                new JobInstance(1L, "endOfDaySettlementJob"), new JobParameters());
    }
}

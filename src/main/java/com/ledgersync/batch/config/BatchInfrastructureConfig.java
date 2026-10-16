package com.ledgersync.batch.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * FR-025a: settlement runs execute strictly one at a time.
 *
 * <p>A synchronous task executor serialises runs within this JVM. That alone is not enough — the
 * moment the service is deployed more than once, two instances could both launch during a rolling
 * restart, and a batch service that silently double-runs against a ledger is a financial
 * incident. {@link SettlementRunLock} extends the guarantee across instances (research R7).
 */
@Configuration
public class BatchInfrastructureConfig {

    @Bean
    @Primary
    public JobLauncher serialJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        // Synchronous on purpose: the caller blocks until the run finishes, so a second file
        // cannot start while one is in flight.
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }
}

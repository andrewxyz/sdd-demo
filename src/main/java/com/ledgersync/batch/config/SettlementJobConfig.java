package com.ledgersync.batch.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.ledgersync.batch.domain.ActiveAccountDirectory;
import com.ledgersync.batch.domain.ReconciliationResult;
import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.ingestion.MalformedRowException;
import com.ledgersync.batch.ingestion.TransactionFileReader;
import com.ledgersync.batch.observability.ExecutionIdJobListener;
import com.ledgersync.batch.observability.SettlementChunkListener;
import com.ledgersync.batch.persistence.BalanceApplier;
import com.ledgersync.batch.persistence.RejectedRecordWriter;
import com.ledgersync.batch.persistence.SettlementRunService;
import com.ledgersync.batch.persistence.StagedTransactionWriter;
import com.ledgersync.batch.persistence.entity.SettlementRun;
import com.ledgersync.batch.persistence.repository.RejectedRecordRepository;
import com.ledgersync.batch.persistence.repository.StagedTransactionRepository;
import com.ledgersync.batch.reconciliation.RecordRejectedException;
import com.ledgersync.batch.reconciliation.RecordValidator;
import com.ledgersync.batch.reconciliation.ReconciliationFailedException;
import com.ledgersync.batch.reconciliation.ReconciliationService;
import com.ledgersync.batch.reconciliation.ThresholdSkipPolicy;
import com.ledgersync.batch.reconciliation.ValidationProcessor;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The settlement job: stage → reconcile → apply (research R2).
 *
 * <p>The split is forced by clarification Q1. A file must be proven to balance against its full
 * header <em>before</em> anything is applied, and a single-pass chunked job cannot honour that —
 * by the time chunk 500 reveals the file does not balance, chunks 1 to 499 are already committed
 * to balances. Staging separates "what did the file say" from "what shall we do about it", which
 * is what makes FR-021's promise structurally true rather than merely intended.
 */
@Configuration
public class SettlementJobConfig {

    public static final String JOB_NAME = "endOfDaySettlementJob";
    public static final String PARAM_EXECUTION_ID = "executionId";
    public static final String PARAM_PLAINTEXT_PATH = "plaintextPath";
    public static final String PARAM_BUSINESS_DATE = "businessDate";

    private final SettlementProperties properties;

    public SettlementJobConfig(SettlementProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Job endOfDaySettlementJob(
            JobRepository jobRepository,
            Step stageTransactionsStep,
            Step reconcileStep,
            Step applyBalancesStep,
            ExecutionIdJobListener executionIdJobListener,
            SettlementOutcomeListener settlementOutcomeListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(executionIdJobListener)
                // Registered after the MDC listener so the failure write still carries the
                // execution id in its log context.
                .listener(settlementOutcomeListener)
                .start(stageTransactionsStep)
                .next(reconcileStep)
                .next(applyBalancesStep)
                .build();
    }

    // ---------------------------------------------------------------- stage

    /**
     * Chunked at the constitutional default of 1000. This is the heavy step — 500,000 rows — and
     * it stays fully chunk-oriented; the documented deviation applies only to the apply step.
     */
    @Bean
    public Step stageTransactionsStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<TransactionRecord> transactionItemReader,
            ValidationProcessor validationProcessor,
            ItemWriter<TransactionRecord> stagedTransactionItemWriter,
            ThresholdSkipPolicy thresholdSkipPolicy,
            SettlementChunkListener chunkListener,
            RejectedRecordSkipListener skipListener) {
        return new StepBuilder("stageTransactions", jobRepository)
                .<TransactionRecord, TransactionRecord>chunk(
                        properties.chunkSize(), transactionManager)
                .reader(transactionItemReader)
                .processor(validationProcessor)
                .writer(stagedTransactionItemWriter)
                .faultTolerant()
                // FR-014: a bad row does not stop the read. FR-015 bounds how far that goes.
                .skipPolicy(thresholdSkipPolicy)
                .skip(RecordRejectedException.class)
                .skip(MalformedRowException.class)
                .listener(skipListener)
                .listener(chunkListener)
                .listener((org.springframework.batch.core.StepExecutionListener) thresholdSkipPolicyListener(
                        thresholdSkipPolicy))
                .build();
    }

    private org.springframework.batch.core.StepExecutionListener thresholdSkipPolicyListener(
            ThresholdSkipPolicy policy) {
        return new org.springframework.batch.core.StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                // The policy needs the live read count; SkipPolicy is handed only the skip count.
                policy.bindStepExecution(stepExecution);
            }
        };
    }

    /**
     * Declared as {@link FlatFileItemReader}, not {@code ItemReader}.
     *
     * <p>The step-scoped proxy takes the declared return type. Declaring the interface hides
     * {@code ItemStream}, so Spring Batch never registers the reader as a stream and never opens
     * it — every read then throws "Reader must be open before it can be read", every row is
     * skipped, and the run grinds through the whole file rejecting everything.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<TransactionRecord> transactionItemReader(
            TransactionFileReader readerFactory,
            @Value("#{jobParameters['" + PARAM_PLAINTEXT_PATH + "']}") String plaintextPath) {
        return readerFactory.build(Path.of(plaintextPath));
    }

    @Bean
    @StepScope
    public ValidationProcessor validationProcessor(
            ActiveAccountDirectory accountDirectory,
            @Value("#{jobParameters['" + PARAM_BUSINESS_DATE + "']}") String businessDate) {
        RecordValidator validator = new RecordValidator(
                accountDirectory, Set.copyOf(properties.settlementTypes()));
        return new ValidationProcessor(validator, LocalDate.parse(businessDate));
    }

    @Bean
    @StepScope
    public ItemWriter<TransactionRecord> stagedTransactionItemWriter(
            StagedTransactionRepository repository,
            @Value("#{jobParameters['" + PARAM_EXECUTION_ID + "']}") String executionId) {
        return new StagedTransactionWriter(repository, UUID.fromString(executionId));
    }

    @Bean
    public ThresholdSkipPolicy thresholdSkipPolicy() {
        return new ThresholdSkipPolicy(
                properties.rejectThreshold().ratio(), properties.rejectThreshold().minRecords());
    }

    // ------------------------------------------------------------ reconcile

    /**
     * A tasklet, not a chunk loop: this step reads two aggregates and makes one decision.
     */
    @Bean
    public Step reconcileStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SettlementRunService runService,
            StagedTransactionRepository stagedRepository,
            RejectedRecordRepository rejectedRepository,
            ReconciliationService reconciliationService) {
        return new StepBuilder("reconcile", jobRepository)
                .tasklet(reconcileTasklet(
                        runService, stagedRepository, rejectedRepository, reconciliationService),
                        transactionManager)
                .build();
    }

    private Tasklet reconcileTasklet(
            SettlementRunService runService,
            StagedTransactionRepository stagedRepository,
            RejectedRecordRepository rejectedRepository,
            ReconciliationService reconciliationService) {
        return (contribution, chunkContext) -> {
            UUID executionId = UUID.fromString((String) chunkContext.getStepContext()
                    .getJobParameters().get(PARAM_EXECUTION_ID));
            SettlementRun run = runService.require(executionId);

            long observedCount = stagedRepository.countByExecutionId(executionId);
            BigDecimal observedNetTotal =
                    stagedRepository.sumSignedAmount(executionId).orElse(BigDecimal.ZERO);
            long rejectedCount = rejectedRepository.countByExecutionId(executionId);

            ReconciliationResult result = reconciliationService.reconcile(
                    run.getDeclaredCount() == null ? 0L : run.getDeclaredCount(),
                    run.getDeclaredNetTotal(),
                    observedCount,
                    observedNetTotal,
                    rejectedCount);

            runService.recordReconciliation(executionId, result);
            runService.recordCounts(
                    executionId, observedCount + rejectedCount, rejectedCount, run.isEarlyStopped());

            if (!result.reconciled()) {
                // Stops the job before applyBalances can run. No balance has been touched, and
                // none will be (FR-021).
                //
                // The run is marked FAILED by SettlementOutcomeListener.afterJob, not here.
                // Marking it here would mean a REQUIRES_NEW update of the settlement_run row
                // this very transaction has already locked — a self-deadlock that hangs the run
                // instead of failing it. The reason is handed over through the execution context.
                SettlementOutcomeListener.carryFigures(
                        chunkContext.getStepContext().getStepExecution().getJobExecution()
                                .getExecutionContext(),
                        result);
                throw new ReconciliationFailedException(result);
            }
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public ReconciliationService reconciliationService() {
        return new ReconciliationService();
    }

    // ---------------------------------------------------------------- apply

    @Bean
    public Step applyBalancesStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BalanceApplier balanceApplier,
            SettlementRunService runService,
            java.time.Clock clock) {
        return new StepBuilder("applyBalances", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    UUID executionId = UUID.fromString((String) chunkContext.getStepContext()
                            .getJobParameters().get(PARAM_EXECUTION_ID));
                    // One statement, one transaction, one row per account (research R3).
                    int accountsUpdated = balanceApplier.apply(executionId, clock.instant());
                    runService.markSettled(executionId, accountsUpdated);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    // ------------------------------------------------------------ listeners

    /**
     * Bridges Spring Batch's skip callback to the durable reject write. Kept as its own type so
     * the {@code REQUIRES_NEW} boundary lives in one place.
     */
    @Bean
    @StepScope
    public RejectedRecordSkipListener rejectedRecordSkipListener(
            RejectedRecordWriter writer,
            @Value("#{jobParameters['" + PARAM_EXECUTION_ID + "']}") String executionId) {
        return new RejectedRecordSkipListener(writer, UUID.fromString(executionId));
    }
}

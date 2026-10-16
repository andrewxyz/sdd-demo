package com.ledgersync.batch.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.ledgersync.batch.config.SettlementJobConfig;
import com.ledgersync.batch.persistence.SettlementRunService;
import com.ledgersync.batch.persistence.entity.SettlementRun;
import com.ledgersync.batch.persistence.repository.CustomerBalanceRepository;
import com.ledgersync.batch.persistence.repository.RejectedRecordRepository;
import com.ledgersync.batch.persistence.repository.SettlementRunRepository;
import com.ledgersync.batch.persistence.repository.StagedTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers base for integration tests.
 *
 * <p>Runs against real PostgreSQL rather than an in-memory substitute, because three things this
 * design depends on are engine behaviour and not portable: {@code NUMERIC} exactness, the
 * {@code UPDATE ... FROM} apply statement, and the unique-index race that guards redelivery.
 *
 * <p>Named {@code *IT} so Failsafe runs it. Surefire excludes the pattern, keeping {@code mvn
 * test} free of Docker as the Constitution Development Workflow requires (T081).
 */
@SpringBootTest
public abstract class AbstractPostgresIT {

    /**
     * An already-running PostgreSQL to test against, supplied as {@code -Dit.jdbc.url=...} or
     * {@code IT_JDBC_URL}.
     *
     * <p>Testcontainers is the default and needs no setup. This escape hatch exists because
     * Testcontainers' bundled docker-java negotiates Docker API 1.32, and Docker Engine 29
     * refuses anything below 1.44 — on such a host the container cannot start at all, even
     * though Docker itself is perfectly usable. Rather than leave the suite unrunnable there,
     * point it at a database started by any means:
     *
     * <pre>
     * docker run -d --name ledgersync-it -e POSTGRES_DB=ledgersync \
     *   -e POSTGRES_USER=ledgersync -e POSTGRES_PASSWORD=ledgersync -p 55432:5432 postgres:16
     * mvn verify -Dit.jdbc.url=jdbc:postgresql://localhost:55432/ledgersync
     * </pre>
     */
    private static final String EXTERNAL_URL = System.getProperty(
            "it.jdbc.url", System.getenv("IT_JDBC_URL"));

    private static final PostgreSQLContainer<?> POSTGRES = startContainerIfNeeded();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> startContainerIfNeeded() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            return null;
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("ledgersync")
                .withUsername("ledgersync")
                .withPassword("ledgersync");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES == null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getProperty("it.jdbc.user", "ledgersync"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty("it.jdbc.password", "ledgersync"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }

    @Autowired
    protected JobLauncher jobLauncher;

    @Autowired
    protected Job endOfDaySettlementJob;

    @Autowired
    protected SettlementRunRepository runRepository;

    @Autowired
    protected StagedTransactionRepository stagedRepository;

    @Autowired
    protected RejectedRecordRepository rejectedRepository;

    @Autowired
    protected CustomerBalanceRepository balanceRepository;

    @Autowired
    protected SettlementRunService runService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM settlement_rejected_record");
        jdbcTemplate.update("DELETE FROM settlement_staged_transaction");
        jdbcTemplate.update("DELETE FROM settlement_run");
        jdbcTemplate.update("DELETE FROM customer_balance");
    }

    protected void seedAccount(String accountNumber, String balance) {
        jdbcTemplate.update(
                "INSERT INTO customer_balance (account_number, balance, status) VALUES (?, ?, 'ACTIVE')",
                accountNumber, new BigDecimal(balance));
    }

    protected BigDecimal balanceOf(String accountNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM customer_balance WHERE account_number = ?",
                BigDecimal.class, accountNumber);
    }

    /** Creates the run row the job expects, as the orchestrator would. */
    protected UUID startRun(
            String fileName, String fingerprint, LocalDate businessDate,
            long declaredCount, String declaredNetTotal) {
        UUID executionId = UUID.randomUUID();
        runService.start(executionId, fileName, fingerprint, businessDate);
        runService.recordHeader(executionId, declaredCount, new BigDecimal(declaredNetTotal));
        return executionId;
    }

    protected JobExecution runJob(UUID executionId, java.nio.file.Path plaintext,
            LocalDate businessDate) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(SettlementJobConfig.PARAM_EXECUTION_ID, executionId.toString())
                .addString(SettlementJobConfig.PARAM_PLAINTEXT_PATH, plaintext.toString())
                .addString(SettlementJobConfig.PARAM_BUSINESS_DATE, businessDate.toString())
                .toJobParameters();
        return jobLauncher.run(endOfDaySettlementJob, parameters);
    }

    /**
     * Runs the job and asserts it did not complete.
     *
     * <p>{@code jobLauncher.run} returns normally when a job <em>fails</em> — it throws only for
     * launch problems such as a duplicate instance. Asserting on a thrown exception therefore
     * tests nothing; the status is the signal.
     */
    protected JobExecution runJobExpectingFailure(
            UUID executionId, java.nio.file.Path plaintext, LocalDate businessDate)
            throws Exception {
        JobExecution execution = runJob(executionId, plaintext, businessDate);
        org.assertj.core.api.Assertions.assertThat(execution.getStatus())
                .as("job should not have completed")
                .isNotEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        return execution;
    }

    protected SettlementRun reload(UUID executionId) {
        return runRepository.findByExecutionId(executionId).orElseThrow();
    }
}

package com.ledgersync.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.ledgersync.batch.fixtures.SettlementFixtures;
import com.ledgersync.batch.persistence.RejectedRecordWriter;
import com.ledgersync.batch.reconciliation.RecordRejectedException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The regression test for the critical finding C1.
 *
 * <p>Spring Batch rolls the chunk back when an item is skipped, then replays it item by item. If
 * {@link RejectedRecordWriter} joined that transaction, the rejected row would be rolled back
 * with it — while the run's {@code records_rejected} counter still said 7. A lost reject row
 * looks exactly like a clean chunk, which is why this needs its own test rather than being
 * assumed from {@code RejectedRecordIT}.
 *
 * <p>Proves FR-013, SC-004, Constitution Principle I.
 */
class RejectSurvivesRollbackIT extends AbstractPostgresIT {

    @Autowired
    private RejectedRecordWriter rejectedRecordWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a reject written inside a rolled-back transaction still survives")
    @Transactional(propagation = Propagation.NEVER)
    void reject_survives_an_enclosing_rollback() {
        UUID executionId = startRun("txn-20261016-11.csv.pgp", "fp-rollback-011",
                SettlementFixtures.BUSINESS_DATE, 1L, "1.00");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                rejectedRecordWriter.recordSkip(executionId, new RecordRejectedException(
                        42L, "accountNumber", "Account is not recognised or is not active",
                        "D,GB29NWBK60161331926819,10.00,CREDIT,2026-10-16T09:00:00+00:00"));
                // Simulates the chunk rollback Spring Batch performs on skip.
                status.setRollbackOnly();
            }
        });

        assertThat(rejectedRepository.countByExecutionId(executionId))
                .as("REQUIRES_NEW must commit the reject independently of the chunk transaction")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the surviving row keeps its reason and masked excerpt intact")
    @Transactional(propagation = Propagation.NEVER)
    void the_surviving_row_is_complete() {
        UUID executionId = startRun("txn-20261016-12.csv.pgp", "fp-rollback-012",
                SettlementFixtures.BUSINESS_DATE, 1L, "1.00");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                rejectedRecordWriter.recordSkip(executionId, new RecordRejectedException(
                        7L, "amount", "Amount has more than 2 decimal places",
                        "D," + SettlementFixtures.ACCOUNT_A + ",10.005,CREDIT,x"));
                status.setRollbackOnly();
            }
        });

        var rejects = rejectedRepository.findByExecutionIdOrderBySourceRowNumberAsc(executionId);
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).getSourceRowNumber()).isEqualTo(7L);
        assertThat(rejects.get(0).getFailingField()).isEqualTo("amount");
        assertThat(rejects.get(0).getRawRowExcerpt())
                .doesNotContain(SettlementFixtures.ACCOUNT_A);
    }
}

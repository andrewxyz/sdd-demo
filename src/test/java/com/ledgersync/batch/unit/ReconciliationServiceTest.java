package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.ledgersync.batch.domain.ReconciliationResult;
import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.reconciliation.ReconciliationService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-018 to FR-020, including the rule clarification Q1 settled: comparison is against the full
 * header, unreduced by rejects.
 *
 * <p>No Spring context, no database.
 */
class ReconciliationServiceTest {

    private final ReconciliationService service = new ReconciliationService();

    @Test
    @DisplayName("agrees when count and signed total both match and nothing was rejected")
    void reconciles_a_clean_file() {
        ReconciliationResult result = service.reconcile(
                3L, new BigDecimal("-150.00"), 3L, new BigDecimal("-150.00"), 0L);

        assertThat(result.reconciled()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("compares net totals by value, so -150.00 and -150.0000 agree")
    void compares_scale_independently() {
        ReconciliationResult result = service.reconcile(
                1L, new BigDecimal("-150.00"), 1L, new BigDecimal("-150.0000"), 0L);

        assertThat(result.reconciled()).isTrue();
    }

    @Test
    @DisplayName("FR-021: a net total mismatch fails and names the reason")
    void fails_on_net_total_mismatch() {
        ReconciliationResult result = service.reconcile(
                3L, new BigDecimal("-150.00"), 3L, new BigDecimal("-149.00"), 0L);

        assertThat(result.reconciled()).isFalse();
        assertThat(result.netTotalMatches()).isFalse();
        assertThat(result.countMatches()).isTrue();
        assertThat(result.failureReason()).contains("net total");
    }

    @Test
    @DisplayName("FR-021: a record count mismatch fails")
    void fails_on_count_mismatch() {
        ReconciliationResult result = service.reconcile(
                1000L, new BigDecimal("10.00"), 998L, new BigDecimal("10.00"), 0L);

        assertThat(result.reconciled()).isFalse();
        assertThat(result.failureReason()).contains("record count");
    }

    @Test
    @DisplayName("clarification Q1: any rejected row makes the file unreconcilable, "
            + "even when the surviving rows happen to add up")
    void rejects_cannot_be_netted_out_of_the_comparison() {
        // The 7 rejected rows are not counted in observed, and the remainder agrees with itself —
        // but the header described a file that included them, so nothing is proven.
        ReconciliationResult result = service.reconcile(
                993L, new BigDecimal("500.00"), 993L, new BigDecimal("500.00"), 7L);

        assertThat(result.reconciled()).isFalse();
        assertThat(result.failureReason()).contains("rejected");
    }

    @Test
    @DisplayName("a zero-record file reconciles to nothing")
    void reconciles_an_empty_file() {
        ReconciliationResult result = service.reconcile(
                0L, BigDecimal.ZERO, 0L, null, 0L);

        assertThat(result.reconciled()).isTrue();
        assertThat(result.observedNetTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("FR-018: type supplies direction — CREDIT adds, DEBIT subtracts")
    void signed_amount_follows_the_transaction_type() {
        TransactionRecord credit = record(TransactionRecord.CREDIT, "250.00");
        TransactionRecord debit = record(TransactionRecord.DEBIT, "300.00");

        assertThat(credit.signedAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(debit.signedAmount()).isEqualByComparingTo(new BigDecimal("-300.00"));
    }

    @Test
    @DisplayName("the worked example from the file contract sums to its declared header")
    void matches_the_contract_worked_example() {
        BigDecimal sum = record(TransactionRecord.CREDIT, "250.00").signedAmount()
                .add(record(TransactionRecord.DEBIT, "300.00").signedAmount())
                .add(record(TransactionRecord.DEBIT, "100.00").signedAmount());

        assertThat(sum).isEqualByComparingTo(new BigDecimal("-150.00"));
        assertThat(service.reconcile(3L, new BigDecimal("-150.00"), 3L, sum, 0L).reconciled())
                .isTrue();
    }

    private static TransactionRecord record(String type, String amount) {
        return new TransactionRecord(1L, "GB29NWBK60161331926819", type,
                new BigDecimal(amount), java.time.Instant.parse("2026-10-16T09:14:22Z"), "raw");
    }
}

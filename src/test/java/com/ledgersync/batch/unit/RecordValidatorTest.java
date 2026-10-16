package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import com.ledgersync.batch.domain.ActiveAccountDirectory;
import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.domain.ValidationOutcome;
import com.ledgersync.batch.reconciliation.RecordValidator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-008 to FR-012.
 *
 * <p>Constructed with a lambda and a set — no Spring context, no database. That this test needs
 * neither is the Constitution Development Workflow gate, not an incidental convenience.
 */
class RecordValidatorTest {

    private static final String KNOWN_ACCOUNT = "GB29NWBK60161331926819";
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 16);

    private final ActiveAccountDirectory directory = KNOWN_ACCOUNT::equals;
    private final RecordValidator validator =
            new RecordValidator(directory, Set.of("CREDIT", "DEBIT"));

    @Test
    @DisplayName("accepts a well-formed row")
    void accepts_a_valid_record() {
        assertThat(validate(KNOWN_ACCOUNT, "250.00", "CREDIT", "2026-10-16T09:14:22Z").valid())
                .isTrue();
    }

    @Test
    @DisplayName("FR-009: an unknown or inactive account is rejected")
    void rejects_an_unknown_account() {
        ValidationOutcome outcome =
                validate("GB94BARC10201530093459", "10.00", "CREDIT", "2026-10-16T09:00:00Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("accountNumber");
        assertThat(outcome.reason()).contains("not recognised");
    }

    @Test
    @DisplayName("FR-008: a missing account number is rejected")
    void rejects_a_blank_account_number() {
        ValidationOutcome outcome = validate("", "10.00", "CREDIT", "2026-10-16T09:00:00Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("accountNumber");
    }

    @Test
    @DisplayName("FR-009: an account longer than 34 characters is rejected")
    void rejects_an_overlong_account_number() {
        assertThat(validate("A".repeat(35), "10.00", "CREDIT", "2026-10-16T09:00:00Z").valid())
                .isFalse();
    }

    @Test
    @DisplayName("FR-010: 3 decimal places are rejected, never silently rounded")
    void rejects_more_than_two_decimal_places() {
        ValidationOutcome outcome =
                validate(KNOWN_ACCOUNT, "10.005", "CREDIT", "2026-10-16T09:00:00Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("amount");
        assertThat(outcome.reason()).contains("2 decimal places");
    }

    @Test
    @DisplayName("FR-010: trailing zeros are not extra precision — 10.5000 is accepted")
    void accepts_trailing_zeros_beyond_two_places() {
        assertThat(validate(KNOWN_ACCOUNT, "10.5000", "CREDIT", "2026-10-16T09:00:00Z").valid())
                .isTrue();
    }

    @Test
    @DisplayName("FR-010: a negative amount is rejected — direction comes from the type")
    void rejects_a_negative_amount() {
        ValidationOutcome outcome =
                validate(KNOWN_ACCOUNT, "-10.00", "CREDIT", "2026-10-16T09:00:00Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("amount");
    }

    @Test
    @DisplayName("a zero amount is accepted — it moves no balance but is not malformed")
    void accepts_a_zero_amount() {
        assertThat(validate(KNOWN_ACCOUNT, "0.00", "CREDIT", "2026-10-16T09:00:00Z").valid())
                .isTrue();
    }

    @Test
    @DisplayName("FR-011: a type outside the configured set is rejected")
    void rejects_an_unknown_transaction_type() {
        ValidationOutcome outcome =
                validate(KNOWN_ACCOUNT, "10.00", "TRANSFER", "2026-10-16T09:00:00Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("type");
    }

    @Test
    @DisplayName("FR-012: a timestamp outside the declared business date is rejected")
    void rejects_a_timestamp_on_another_day() {
        ValidationOutcome outcome =
                validate(KNOWN_ACCOUNT, "10.00", "CREDIT", "2026-10-15T23:59:59Z");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.failingField()).isEqualTo("timestamp");
        assertThat(outcome.reason()).contains("2026-10-15");
    }

    private ValidationOutcome validate(
            String account, String amount, String type, String timestamp) {
        TransactionRecord record = new TransactionRecord(
                1L, account, type, new BigDecimal(amount), Instant.parse(timestamp), "raw");
        return validator.validate(record, BUSINESS_DATE);
    }
}

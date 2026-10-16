package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgersync.batch.persistence.RejectedRecordWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-031: account-level detail is masked before the excerpt reaches storage.
 *
 * <p>Masking at write time rather than read time matters — masking on read would leave the clear
 * value in the database for anyone with query access.
 */
class MaskingTest {

    @Test
    @DisplayName("leaves only the last four characters of an account-like token")
    void masks_account_numbers() {
        String masked = RejectedRecordWriter.mask(
                "D,GB29NWBK60161331926819,250.00,CREDIT,2026-10-16T09:14:22+01:00");

        assertThat(masked).doesNotContain("GB29NWBK60161331926819");
        assertThat(masked).contains("6819");
    }

    @Test
    @DisplayName("masks every account-like token on the line, not just the first")
    void masks_all_occurrences() {
        String masked = RejectedRecordWriter.mask(
                "D,GB29NWBK60161331926819,GB94BARC10201530093459");

        assertThat(masked).doesNotContain("GB29NWBK60161331926819");
        assertThat(masked).doesNotContain("GB94BARC10201530093459");
    }

    @Test
    @DisplayName("short tokens such as the record marker and type survive unmasked")
    void leaves_structural_tokens_readable() {
        String masked = RejectedRecordWriter.mask("D,ABC12,250.00,CREDIT,x");

        assertThat(masked).startsWith("D,");
        assertThat(masked).contains("250.00");
    }

    @Test
    @DisplayName("the transaction type stays readable — an operator needs it to diagnose the row")
    void does_not_mask_the_transaction_type() {
        String masked = RejectedRecordWriter.mask(
                "D,,10.00,CREDIT,2026-10-16T09:00:00+00:00");

        assertThat(masked)
                .as("CREDIT is six characters and must not be mistaken for an account number")
                .contains("CREDIT");
        assertThat(RejectedRecordWriter.mask("D,,10.00,DEBIT,x")).contains("DEBIT");
    }

    @Test
    @DisplayName("a null line stays null rather than becoming the string \"null\"")
    void tolerates_a_missing_line() {
        assertThat(RejectedRecordWriter.mask(null)).isNull();
    }

    @Test
    @DisplayName("the excerpt is truncated to the column width so a write cannot fail on length")
    void truncates_to_the_column_width() {
        String masked = RejectedRecordWriter.mask("D," + "x".repeat(2000));

        assertThat(masked).hasSizeLessThanOrEqualTo(512);
    }
}

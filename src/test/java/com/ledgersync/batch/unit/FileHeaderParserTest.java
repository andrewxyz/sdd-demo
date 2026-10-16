package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.ledgersync.batch.domain.FileHeader;
import com.ledgersync.batch.ingestion.FileHeaderParser;
import com.ledgersync.batch.ingestion.HeaderUnreadableException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-017 / FR-023. No Spring context, no database — a Constitution Development Workflow
 * requirement.
 */
class FileHeaderParserTest {

    private final FileHeaderParser parser = new FileHeaderParser();

    @Test
    @DisplayName("parses the declared business date, count and net total")
    void parses_a_well_formed_header() {
        FileHeader header = parser.parse("H,2026-10-16,3,-150.00");

        assertThat(header.businessDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(header.declaredCount()).isEqualTo(3L);
        assertThat(header.declaredNetTotal()).isEqualByComparingTo(new BigDecimal("-150.00"));
    }

    @Test
    @DisplayName("a negative net total is legitimate — a day of net outflow")
    void accepts_a_negative_net_total() {
        assertThat(parser.parse("H,2026-10-16,2,-999999.99").declaredNetTotal())
                .isEqualByComparingTo(new BigDecimal("-999999.99"));
    }

    @Test
    @DisplayName("a zero-record file is valid and reconciles")
    void accepts_a_zero_record_header() {
        FileHeader header = parser.parse("H,2026-10-16,0,0.00");

        assertThat(header.declaredCount()).isZero();
        assertThat(header.declaredNetTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "rejects: {0}")
    @ValueSource(strings = {
            "D,2026-10-16,3,-150.00",       // detail marker where a header belongs
            "H,16-10-2026,3,-150.00",       // not an ISO date
            "H,2026-10-16,three,-150.00",   // count is not an integer
            "H,2026-10-16,3,abc",           // net total is not a decimal
            "H,2026-10-16,3",               // too few fields
            "H,2026-10-16,3,-150.00,extra", // too many fields
            "H,2026-10-16,-3,-150.00"       // negative record count
    })
    @DisplayName("FR-023: an unparseable header fails rather than being guessed at")
    void rejects_malformed_headers(String line) {
        assertThatThrownBy(() -> parser.parse(line))
                .isInstanceOf(HeaderUnreadableException.class);
    }

    @Test
    @DisplayName("FR-023: a missing header fails — correctness cannot be shown without it")
    void rejects_a_missing_header() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(HeaderUnreadableException.class);
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(HeaderUnreadableException.class);
    }
}

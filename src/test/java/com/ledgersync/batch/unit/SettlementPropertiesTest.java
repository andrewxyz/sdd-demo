package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import com.ledgersync.batch.config.SettlementProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-002c: the delivery deadline must leave room for a run plus one corrective resend before the
 * operational cutoff. Validated at construction so a misconfiguration fails at startup rather
 * than at 5am.
 */
class SettlementPropertiesTest {

    @Test
    @DisplayName("accepts a deadline with enough headroom before the cutoff")
    void accepts_a_well_spaced_deadline() {
        assertThatCode(() -> properties(LocalTime.of(3, 0), LocalTime.of(6, 0)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-002c: rejects a deadline too close to the cutoff to act on")
    void rejects_a_deadline_without_headroom() {
        // 05:40 leaves 20 minutes before a 06:00 cutoff — not enough for a 30-minute run, let
        // alone a resend. An alert raised then is useless.
        assertThatThrownBy(() -> properties(LocalTime.of(5, 40), LocalTime.of(6, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FR-002c");
    }

    @Test
    @DisplayName("rejects a non-positive chunk size")
    void rejects_an_invalid_chunk_size() {
        assertThatThrownBy(() -> new SettlementProperties(
                Path.of("landing"), Path.of("archive"), 0,
                new SettlementProperties.RejectThreshold(0.25, 1000),
                LocalTime.of(3, 0), LocalTime.of(6, 0),
                Duration.ofMinutes(30), Duration.ofHours(1), List.of("CREDIT"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Constitution IV: the reject ratio must be a bounded fraction")
    void rejects_an_out_of_range_ratio() {
        assertThatThrownBy(() -> new SettlementProperties.RejectThreshold(1.5, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties.RejectThreshold(0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the shipped defaults satisfy their own rule")
    void shipped_defaults_are_valid() {
        SettlementProperties defaults = properties(LocalTime.of(3, 0), LocalTime.of(6, 0));

        assertThat(defaults.chunkSize()).isEqualTo(1000);
        assertThat(defaults.rejectThreshold().ratio()).isEqualTo(0.25);
        assertThat(defaults.rejectThreshold().minRecords()).isEqualTo(1000);
    }

    private static SettlementProperties properties(LocalTime deadline, LocalTime cutoff) {
        return new SettlementProperties(
                Path.of("landing"), Path.of("archive"), 1000,
                new SettlementProperties.RejectThreshold(0.25, 1000),
                deadline, cutoff,
                Duration.ofMinutes(30), Duration.ofHours(1),
                List.of("CREDIT", "DEBIT"), null, null);
    }
}

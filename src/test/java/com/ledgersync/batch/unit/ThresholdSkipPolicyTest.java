package com.ledgersync.batch.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgersync.batch.reconciliation.ThresholdSkipPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * FR-015: stop above 25% rejected, but only once 1,000 records have been read.
 *
 * <p>{@code true} means "skip this item and keep reading"; {@code false} means "abandon the file".
 */
class ThresholdSkipPolicyTest {

    private final ThresholdSkipPolicy policy = new ThresholdSkipPolicy(0.25, 1000);

    @Nested
    @DisplayName("below the 1,000-record floor")
    class BelowTheFloor {

        @Test
        @DisplayName("never trips, however bad the ratio looks")
        void small_files_always_complete_their_pass() {
            // The 40-row file with 30 rejects from quickstart Scenario 4: 75% rejected, but it
            // must still report all 30 rather than being abandoned on a tiny sample.
            assertThat(policy.shouldSkip(30, 40)).isTrue();
            assertThat(policy.shouldSkip(999, 999)).isTrue();
        }
    }

    @Nested
    @DisplayName("at or above the floor")
    class AtTheFloor {

        @Test
        @DisplayName("keeps reading below the ratio")
        void tolerates_ordinary_defect_levels() {
            assertThat(policy.shouldSkip(10, 1000)).isTrue();
            assertThat(policy.shouldSkip(5_000, 500_000)).isTrue();
        }

        @Test
        @DisplayName("exactly 25% does not trip — only above it does")
        void the_boundary_is_inclusive() {
            assertThat(policy.shouldSkip(250, 1000)).isTrue();
        }

        @Test
        @DisplayName("above 25% abandons the file")
        void abandons_a_hopeless_file() {
            assertThat(policy.shouldSkip(251, 1000)).isFalse();
            // The ~40% file from quickstart Scenario 4.
            assertThat(policy.shouldSkip(2_000, 5_000)).isFalse();
        }
    }

    @Test
    @DisplayName("the configured figures are explicit and bounded, as Constitution IV requires")
    void exposes_its_bounds() {
        assertThat(policy.getMaxRejectRatio()).isEqualTo(0.25);
        assertThat(policy.getMinRecordsBeforeRatioApplies()).isEqualTo(1000);
    }
}

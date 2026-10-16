package com.ledgersync.batch.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for settlement. Immutable record with constructor binding
 * (Constitution III).
 *
 * <p>FR-002c is validated in the compact constructor, so a deadline that leaves no room to act is
 * a startup failure rather than a 5am surprise.
 */
@ConfigurationProperties(prefix = "ledgersync")
public record SettlementProperties(
        Path landingZone,
        Path archive,
        int chunkSize,
        RejectThreshold rejectThreshold,
        LocalTime deliveryDeadline,
        LocalTime operationalCutoff,
        Duration expectedRunDuration,
        Duration resendWindow,
        List<String> settlementTypes,
        Path privateKeyPath,
        String privateKeyPassphrase) {

    /**
     * FR-015: stop reading early above {@code ratio} rejected, but only once {@code minRecords}
     * have been read. Both bounded and explicit, as Constitution IV requires.
     */
    public record RejectThreshold(double ratio, long minRecords) {

        public RejectThreshold {
            if (ratio <= 0 || ratio > 1) {
                throw new IllegalArgumentException(
                        "ledgersync.reject-threshold.ratio must be in (0,1], was " + ratio);
            }
            if (minRecords < 0) {
                throw new IllegalArgumentException(
                        "ledgersync.reject-threshold.min-records must be >= 0, was " + minRecords);
            }
        }
    }

    public SettlementProperties {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("ledgersync.chunk-size must be positive");
        }
        if (deliveryDeadline != null && operationalCutoff != null) {
            Duration headroom = Duration.between(deliveryDeadline, operationalCutoff);
            Duration required = nullSafe(expectedRunDuration).plus(nullSafe(resendWindow));
            if (headroom.compareTo(required) < 0) {
                throw new IllegalArgumentException(
                        "FR-002c violated: delivery deadline %s leaves only %s before the %s cutoff, "
                                .formatted(deliveryDeadline, headroom, operationalCutoff)
                                + "but a run plus one corrective resend needs %s. An alert raised too late to act on is no alert at all."
                                        .formatted(required));
            }
        }
    }

    private static Duration nullSafe(Duration d) {
        return d == null ? Duration.ZERO : d;
    }
}

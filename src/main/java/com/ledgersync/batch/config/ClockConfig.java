package com.ledgersync.batch.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * A single injected {@link Clock} rather than calls to {@code Instant.now()} scattered through
 * the code.
 *
 * <p>This is what lets the alert-latency assertion (SC-006) and the deadline monitor be tested
 * deterministically, instead of with wall-clock sleeps that are slow and flaky.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

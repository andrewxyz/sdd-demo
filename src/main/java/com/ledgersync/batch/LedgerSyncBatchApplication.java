package com.ledgersync.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the LedgerSync-Batch end-of-day settlement service.
 *
 * <p>Scheduling is enabled for the missed-delivery monitor (FR-002b); the settlement job itself is
 * triggered by sentinel arrival, not by a schedule.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerSyncBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerSyncBatchApplication.class, args);
    }
}

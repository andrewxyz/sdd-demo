package com.ledgersync.batch.ingestion;

import com.ledgersync.batch.SettlementOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FR-001: begins processing without manual intervention.
 *
 * <p>Polling the landing zone rather than watching it with a filesystem event API is deliberate.
 * The trigger is the sentinel, not the data file (FR-002), and a watch service gives no advantage
 * when the thing being waited for is a second small file that arrives seconds later. Polling also
 * survives a restart and an NFS- or SMB-mounted zone, where change notifications are unreliable.
 *
 * <p>Disabled by setting {@code ledgersync.poll.enabled=false}, which the test profile does so
 * that integration tests are not raced by a background run.
 */
@Component
@ConditionalOnProperty(name = "ledgersync.poll.enabled", havingValue = "true", matchIfMissing = true)
public class LandingZonePoller {

    private static final Logger log = LoggerFactory.getLogger(LandingZonePoller.class);

    private final SettlementOrchestrator orchestrator;

    public LandingZonePoller(SettlementOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${ledgersync.poll.interval:PT30S}")
    public void poll() {
        try {
            int processed = orchestrator.runOnce();
            if (processed > 0) {
                log.info("Settled {} file(s) in this scan", processed);
            }
        } catch (Exception e) {
            // Never propagate: an exception out of a scheduled method silently cancels the
            // schedule in some configurations, which would stop the service processing anything
            // further without any obvious signal.
            log.error("Landing zone scan failed; will retry on the next poll", e);
        }
    }
}

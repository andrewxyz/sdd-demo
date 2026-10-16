package com.ledgersync.batch.ingestion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import com.ledgersync.batch.alerting.AlertDispatcher;
import com.ledgersync.batch.alerting.dto.AlertType;
import com.ledgersync.batch.config.SettlementProperties;
import com.ledgersync.batch.persistence.repository.SettlementRunRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FR-002b: raises {@code DELIVERY_OVERDUE} when a business date has no settled run by its
 * deadline.
 *
 * <p>This exists because of what the sentinel design costs: a data file whose sentinel never
 * arrives is never read, and an absent delivery produces no error at all. Without this monitor,
 * both look identical to a quiet night — the system would be silently doing nothing and
 * reporting success by omission (SC-010).
 */
@Component
public class DeliveryDeadlineMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDeadlineMonitor.class);

    private final SettlementProperties properties;
    private final SettlementRunRepository runRepository;
    private final AlertDispatcher alertDispatcher;
    private final Clock clock;

    public DeliveryDeadlineMonitor(
            SettlementProperties properties,
            SettlementRunRepository runRepository,
            AlertDispatcher alertDispatcher,
            Clock clock) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.alertDispatcher = alertDispatcher;
        this.clock = clock;
    }

    /** Checked every 15 minutes; alerts only once the deadline for the date has passed. */
    @Scheduled(fixedDelayString = "PT15M")
    public void checkOverdueDeliveries() {
        LocalDate businessDate = LocalDate.now(clock).minusDays(1);
        if (!deadlinePassedFor(businessDate)) {
            return;
        }
        check(businessDate);
    }

    /** Separated from the schedule so it can be driven directly in tests. */
    public boolean check(LocalDate businessDate) {
        if (!runRepository.noSettledRunFor(businessDate)) {
            return false;
        }
        log.warn("No settled delivery for business date {} by its deadline", businessDate);
        alertDispatcher.simple(
                AlertType.DELIVERY_OVERDUE,
                UUID.randomUUID(),
                "(no file received)",
                businessDate,
                "PENDING",
                "No completed delivery for business date %s by the %s deadline"
                        .formatted(businessDate, properties.deliveryDeadline()));
        return true;
    }

    private boolean deadlinePassedFor(LocalDate businessDate) {
        LocalTime deadline = properties.deliveryDeadline();
        if (deadline == null) {
            return false;
        }
        return LocalTime.now(clock.withZone(ZoneId.systemDefault())).isAfter(deadline);
    }
}

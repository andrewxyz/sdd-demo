package com.ledgersync.batch.alerting;

import com.ledgersync.batch.alerting.dto.AlertPayload;

/**
 * Outbound port for urgent settlement alerts.
 *
 * <p>The spec places routing and escalation policy out of scope, so this is a seam rather than a
 * channel. Implementations are bound at deployment time.
 */
public interface SettlementAlertPublisher {

    /**
     * Publish an alert. Implementations MUST NOT throw: a channel failure is logged and swallowed
     * by the dispatcher, because the ledger outcome is already decided and must not depend on a
     * notification channel (contracts/alert-hook.md section 4).
     */
    void publish(AlertPayload payload);
}

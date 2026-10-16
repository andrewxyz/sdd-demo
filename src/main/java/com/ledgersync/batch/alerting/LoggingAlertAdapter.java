package com.ledgersync.batch.alerting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgersync.batch.alerting.dto.AlertPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link SettlementAlertPublisher}: emits the payload as a structured JSON log event.
 *
 * <p>The spec puts routing and escalation out of scope, so this is the fallback binding. A
 * deployment supplies its own adapter and this one steps aside
 * ({@code @ConditionalOnMissingBean}).
 */
@Component
@ConditionalOnMissingBean(ignored = LoggingAlertAdapter.class, value = SettlementAlertPublisher.class)
public class LoggingAlertAdapter implements SettlementAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger("SETTLEMENT_ALERT");

    private final ObjectMapper objectMapper;

    /**
     * Builds its own mapper rather than injecting one. This service has no web starter, so no
     * {@code ObjectMapper} bean exists in the context — and a batch service should not acquire a
     * web dependency merely to serialise one payload.
     */
    public LoggingAlertAdapter() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publish(AlertPayload payload) {
        try {
            // Serialised wholesale rather than interpolated field by field: the payload type is
            // the disclosure contract, so anything it does not carry cannot leak here.
            log.error(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("Alert {} could not be serialised", payload.idempotencyKey(), e);
        }
    }
}

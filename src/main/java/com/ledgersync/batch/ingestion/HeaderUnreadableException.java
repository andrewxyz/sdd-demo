package com.ledgersync.batch.ingestion;

/**
 * FR-023: the header is absent or unparseable, so reconciliation has nothing to compare against
 * and the run cannot be proven correct.
 */
public class HeaderUnreadableException extends RuntimeException {

    public HeaderUnreadableException(String message) {
        super(message);
    }
}

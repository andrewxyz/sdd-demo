package com.ledgersync.batch.ingestion;

/**
 * FR-003: the file could not be opened. The run is FAILED with zero records read, and an urgent
 * alert is raised.
 */
public class DecryptionFailedException extends RuntimeException {

    public DecryptionFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecryptionFailedException(String message) {
        super(message);
    }
}

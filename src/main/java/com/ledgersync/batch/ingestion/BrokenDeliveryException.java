package com.ledgersync.batch.ingestion;

/**
 * FR-002a: a sentinel arrived naming a data file that is absent, unreadable, or misnamed.
 *
 * <p>Distinct from a late delivery: the producer has signalled completion, so waiting longer will
 * not help. This warrants an alert now rather than at the delivery deadline.
 */
public class BrokenDeliveryException extends RuntimeException {

    private final String dataFileName;

    public BrokenDeliveryException(String dataFileName) {
        super("Sentinel names a data file that is absent or unreadable: " + dataFileName);
        this.dataFileName = dataFileName;
    }

    public String getDataFileName() {
        return dataFileName;
    }
}

package com.ledgersync.batch.reconciliation;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;

/**
 * FR-015: stop reading early above {@code ratio} rejected, evaluated only once {@code minRecords}
 * have been read.
 *
 * <p>Spring Batch's built-in skip limit is an absolute count, so it cannot express a
 * percentage-with-a-floor rule. {@link SkipPolicy} is the framework's own extension point for
 * exactly this, so a custom policy is the intended mechanism rather than a workaround.
 *
 * <p>Why the floor matters: without it, a 40-row file with 30 bad rows would trip at 25% and the
 * operator would get a truncated list. With it, small files always report in full, and the rule
 * catches only what it is for — the wrong file or the wrong format entirely (research R5).
 *
 * <p>Constitution IV requires skip limits to be explicit and bounded. Both figures here are
 * configured, and neither is unlimited.
 */
public class ThresholdSkipPolicy implements SkipPolicy {

    private final double maxRejectRatio;
    private final long minRecordsBeforeRatioApplies;

    /**
     * Captured so the policy can read the live read count. {@link SkipPolicy} is handed only the
     * skip count, and a ratio needs both — guessing the denominator would make the threshold
     * meaningless.
     */
    private volatile StepExecution stepExecution;

    public ThresholdSkipPolicy(double maxRejectRatio, long minRecordsBeforeRatioApplies) {
        this.maxRejectRatio = maxRejectRatio;
        this.minRecordsBeforeRatioApplies = minRecordsBeforeRatioApplies;
    }

    @BeforeStep
    public void bindStepExecution(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        StepExecution execution = this.stepExecution;
        if (execution == null) {
            // Not yet bound to a step: keep reading rather than abandoning on no information.
            return true;
        }
        return shouldSkip(skipCount, execution.getReadCount());
    }

    /**
     * The decision itself, expressed over plain numbers so it can be unit-tested without a
     * {@code StepExecution}.
     *
     * @return true to skip and keep reading, false to abandon the file
     */
    public boolean shouldSkip(long skipCount, long readCount) {
        if (readCount < minRecordsBeforeRatioApplies) {
            // Below the floor the ratio is not yet meaningful; always keep reading.
            return true;
        }
        double ratio = (double) skipCount / (double) readCount;
        // At exactly the threshold we continue; only above it do we abandon.
        return ratio <= maxRejectRatio;
    }

    public double getMaxRejectRatio() {
        return maxRejectRatio;
    }

    public long getMinRecordsBeforeRatioApplies() {
        return minRecordsBeforeRatioApplies;
    }
}

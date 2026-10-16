package com.ledgersync.batch.domain;

/**
 * Answers FR-009: is this account recognised and active?
 *
 * <p>Declared in {@code domain} as a port so the reconciliation layer can ask the question without
 * importing a repository. That is what keeps {@code RecordValidator} unit-testable with no
 * database and no Spring context, as Constitution II requires — a test supplies a lambda.
 */
@FunctionalInterface
public interface ActiveAccountDirectory {

    boolean isActive(String accountNumber);
}

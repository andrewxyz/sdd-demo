package com.ledgersync.batch.persistence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ledgersync.batch.domain.ActiveAccountDirectory;
import com.ledgersync.batch.persistence.repository.CustomerBalanceRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed {@link ActiveAccountDirectory} (FR-009).
 *
 * <p>Caches the answer per account for the life of a run. Without this, validating 500,000 rows
 * would issue 500,000 lookups, and the account set does not change mid-run — the file is a
 * snapshot of a closed business day.
 */
@Component
public class RepositoryActiveAccountDirectory implements ActiveAccountDirectory {

    private final CustomerBalanceRepository repository;
    private final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

    public RepositoryActiveAccountDirectory(CustomerBalanceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public boolean isActive(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return false;
        }
        return cache.computeIfAbsent(accountNumber, this::lookup);
    }

    private boolean lookup(String accountNumber) {
        Set<String> active = repository.findActiveAmong(Set.of(accountNumber));
        return active.contains(accountNumber);
    }

    /** Cleared between runs so a newly activated account is seen by the next file. */
    public void clearCache() {
        cache.clear();
    }
}

package com.ledgersync.batch.config;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-instance mutual exclusion for settlement runs (FR-025a, research R7).
 *
 * <p>Uses a PostgreSQL session-level advisory lock. The lock is held for the duration of a run and
 * released in a {@code finally} block, so an exception cannot strand it. If another instance holds
 * it, this instance declines to run rather than queueing inside the database — the file stays in
 * the landing zone and is picked up on the next scan.
 */
@Component
public class SettlementRunLock {

    /** Arbitrary but stable key identifying the settlement job across all instances. */
    private static final long LOCK_KEY = 0x1ED6E55ACL;

    private static final Logger log = LoggerFactory.getLogger(SettlementRunLock.class);

    private final JdbcTemplate jdbcTemplate;

    public SettlementRunLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs {@code action} while holding the lock. Returns {@code null} without running it if the
     * lock is already held elsewhere.
     */
    public <T> T withLock(Callable<T> action) throws Exception {
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Settlement lock held by another instance; skipping this scan");
            return null;
        }
        try {
            return action.call();
        } finally {
            jdbcTemplate.queryForObject("select pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        }
    }
}

package com.ledgersync.batch.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a reconciled run's deltas to customer balances (FR-024, FR-025, research R3).
 *
 * <p><strong>One statement, one transaction.</strong> FR-025 requires every balance update in a
 * run to take effect as a whole or not at all, and SC-005 measures it. The naive reading — one
 * transaction wrapped around 500,000 individual updates — is the kind that exhausts rollback
 * segments or times out. Aggregating first collapses the write to the number of <em>distinct
 * accounts touched</em>, which a single transaction handles comfortably.
 *
 * <p>This is the documented deviation from Principle II's chunk mechanics, recorded in plan.md
 * Complexity Tracking. The heavy read — the stage step — remains fully chunked at 1000.
 *
 * <p><strong>Why aggregation is safe here.</strong> Summing deltas is commutative, so the result
 * does not depend on the order rows were read or how work was divided — which is how FR-025b and
 * SC-011 are satisfied by construction. This holds only while the apply path carries no
 * order-sensitive rule. The spec places overdraft policy out of scope; if that ever changes,
 * revisit this class, because an overdraft check cannot be applied to a pre-summed delta.
 */
@Component
public class BalanceApplier {

    private static final String APPLY_SQL = """
            UPDATE customer_balance cb
               SET balance = cb.balance + d.net_delta,
                   last_settled_at = ?
              FROM (SELECT account_number, SUM(signed_amount) AS net_delta
                      FROM settlement_staged_transaction
                     WHERE execution_id = ?
                     GROUP BY account_number) d
             WHERE cb.account_number = d.account_number
            """;

    private final JdbcTemplate jdbcTemplate;

    public BalanceApplier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Joins the caller's transaction ({@code REQUIRED}) rather than opening its own.
     *
     * <p>This matters more than it looks. With {@code REQUIRES_NEW} the update committed
     * independently, so a failure in {@code markSettled} immediately afterwards left balances
     * live while the run said otherwise — a partial settlement, which is exactly what FR-025
     * forbids and SC-005 measures. Joining the step transaction makes "apply the deltas and mark
     * the run SETTLED" succeed or fail as one unit.
     *
     * @return the number of accounts whose balance moved
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public int apply(UUID executionId, Instant completedAt) {
        return jdbcTemplate.update(APPLY_SQL, Timestamp.from(completedAt), executionId);
    }
}

# Phase 1 Data Model: End-of-Day Transaction Settlement

**Date**: 2026-10-16 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Derived from the spec's Key Entities. Tables are created by Liquibase changelogs only
(Constitution IV); `ddl-auto` is `validate`.

Two conventions apply throughout, both from Constitution Principle I:

- Every table has a surrogate `id` for internal use, but **no surrogate id ever leaves the
  persistence layer**. External correlation uses `execution_id` (UUID) and `account_number`
  (business identifier).
- Money is `NUMERIC(19,4)`. No binary floating-point type appears anywhere in this model.

---

## Entity overview

```text
settlement_run (1) ──< settlement_staged_transaction
       │
       ├──< settlement_rejected_record
       │
       └──> (drives) customer_balance   [updated, not owned]
```

`customer_balance` is owned by an existing system of record (spec Assumptions); this feature
updates it but does not create or delete rows in it.

---

## 1. `settlement_run`

One execution against one file. The unit of audit, and the table that makes redelivery detectable.

| Column | Type | Constraints | Source |
|---|---|---|---|
| `id` | BIGSERIAL | PK, internal only | — |
| `execution_id` | UUID | NOT NULL, UNIQUE | FR-004, Constitution V |
| `source_file_name` | VARCHAR(512) | NOT NULL | FR-004 |
| `content_fingerprint` | VARCHAR(64) | NOT NULL, **UNIQUE** | FR-006, R8 |
| `business_date` | DATE | NOT NULL, INDEX | FR-004 |
| `status` | VARCHAR(16) | NOT NULL, CHECK in (PENDING, RUNNING, FAILED, SETTLED) | FR-005 |
| `started_at` | TIMESTAMPTZ | NOT NULL | FR-004 |
| `completed_at` | TIMESTAMPTZ | NULL until terminal | FR-004 |
| `records_read` | BIGINT | NOT NULL DEFAULT 0 | FR-026 |
| `records_rejected` | BIGINT | NOT NULL DEFAULT 0 | FR-026 |
| `records_applied` | BIGINT | NOT NULL DEFAULT 0 | FR-026 |
| `declared_count` | BIGINT | NULL until header parsed | FR-017 |
| `declared_net_total` | NUMERIC(19,4) | NULL until header parsed | FR-017 |
| `observed_count` | BIGINT | NULL until staged | FR-018 |
| `observed_net_total` | NUMERIC(19,4) | NULL until staged | FR-018 |
| `failure_reason` | VARCHAR(256) | NULL unless FAILED | FR-021, FR-023 |
| `early_stopped` | BOOLEAN | NOT NULL DEFAULT false | FR-015a |

**The `content_fingerprint` unique index is the idempotency guarantee.** Enforcing it in the
database rather than with an application-level check closes the race between two near-simultaneous
deliveries of the same file (R8).

### State transitions

```text
PENDING ──> RUNNING ──> SETTLED     (reconciled, balances applied)
                   └──> FAILED      (decrypt failure, no/unreadable header,
                                     any rejected record, totals mismatch,
                                     early stop)
```

No transition out of SETTLED or FAILED. A corrected file creates a *new* run (FR-007); the failed
run is retained (SC-009).

### Validation rules

- `status = SETTLED` requires `records_rejected = 0` and `observed_* = declared_*` — a direct
  encoding of clarification Q1. Worth asserting as a CHECK constraint so the rule cannot be
  violated by a code path that forgets it.
- `completed_at` NOT NULL whenever `status` is terminal.

---

## 2. `settlement_staged_transaction`

Rows read from the file and held pending reconciliation. Never a customer-visible balance until
the apply step runs (R2).

| Column | Type | Constraints | Source |
|---|---|---|---|
| `id` | BIGSERIAL | PK, internal only | — |
| `execution_id` | UUID | NOT NULL, FK → `settlement_run`, INDEX | R2 |
| `source_row_number` | BIGINT | NOT NULL | FR-013 |
| `account_number` | VARCHAR(34) | NOT NULL, INDEX | FR-008 |
| `transaction_type` | VARCHAR(16) | NOT NULL, CHECK in configured set | FR-011 |
| `amount` | NUMERIC(19,4) | NOT NULL | FR-010 |
| `signed_amount` | NUMERIC(19,4) | NOT NULL — amount with direction applied | FR-018 |
| `transaction_timestamp` | TIMESTAMPTZ | NOT NULL | FR-012 |

`signed_amount` is computed once at staging rather than at apply time. This keeps the aggregate in
R3 a plain `SUM()` with no type logic in the query, and it means the sign convention is decided in
the reconciliation layer where it belongs rather than in SQL.

**Index**: `(execution_id, account_number)` — serves both the reconciliation aggregate and the
per-account grouping in the apply step.

**Lifecycle**: rows are deleted for an execution on restart before re-staging (R6), preventing
double-counting. Retention after a terminal run follows the audit standard.

---

## 3. `settlement_rejected_record`

The operator's record of what did not settle. Written by a `SkipListener`.

| Column | Type | Constraints | Source |
|---|---|---|---|
| `id` | BIGSERIAL | PK, internal only | — |
| `execution_id` | UUID | NOT NULL, FK → `settlement_run`, INDEX | FR-013 |
| `source_row_number` | BIGINT | NOT NULL | FR-013 |
| `failing_field` | VARCHAR(64) | NOT NULL | FR-013 |
| `rejection_reason` | VARCHAR(256) | NOT NULL — plain language | FR-013 |
| `raw_row_excerpt` | VARCHAR(512) | NULL, **masked** | FR-013, FR-031 |
| `rejected_at` | TIMESTAMPTZ | NOT NULL | FR-013 |

**`raw_row_excerpt` is the one field in this model that can leak.** It exists so an operator can see
what arrived, but a raw row contains account-level financial detail that FR-031 requires masked.
Account numbers must be masked to their last four characters before the excerpt is written —
masking at write time, not at read time, so the plaintext never reaches storage.

**Index**: `(business_date)` via join, and `(execution_id)` — FR-016 requires query by both.

---

## 4. `customer_balance`

Existing table, owned elsewhere. This feature updates `balance` and `last_settled_at` only.

| Column | Type | Notes |
|---|---|---|
| `account_number` | VARCHAR(34) | Business key — the identifier used across all layers |
| `balance` | NUMERIC(19,4) | Updated by the apply step |
| `last_settled_at` | TIMESTAMPTZ | Set to the run's completion |
| `status` | VARCHAR(16) | Read-only here; drives the active-account check in FR-009 |

The apply step (R3) performs one set-based update:

```text
UPDATE customer_balance cb
   SET balance = cb.balance + d.net_delta,
       last_settled_at = :completedAt
  FROM (SELECT account_number, SUM(signed_amount) AS net_delta
          FROM settlement_staged_transaction
         WHERE execution_id = :executionId
         GROUP BY account_number) d
 WHERE cb.account_number = d.account_number;
```

One statement, one transaction, one row per account touched — this is what makes FR-025 affordable.

---

## 5. In-memory types (records, never entities)

Constitution Principle III: DTOs are `record`, records are never JPA entities.

| Record | Fields | Layer |
|---|---|---|
| `TransactionRecord` | `sourceRowNumber`, `accountNumber`, `type`, `amount`, `timestamp` | ingestion → reconciliation |
| `FileHeader` | `businessDate`, `declaredCount`, `declaredNetTotal` | ingestion → reconciliation |
| `ValidationOutcome` | `valid`, `failingField`, `reason` | reconciliation |
| `ReconciliationResult` | `reconciled`, `declaredCount`, `observedCount`, `declaredNetTotal`, `observedNetTotal`, `rejectedCount` | reconciliation |
| `RejectedRecord` | `sourceRowNumber`, `failingField`, `reason`, `maskedExcerpt` | reconciliation → persistence |
| `AlertPayload` | see [contracts/alert-hook.md](./contracts/alert-hook.md) | alerting |

`ReconciliationResult` deliberately carries both declared and observed figures rather than a bare
boolean, because FR-022 requires the alert to state all four, and recomputing them at alert time
would risk the alert disagreeing with the decision.

---

## Requirements traceability

| Requirement | Where satisfied |
|---|---|
| FR-004, FR-005 | `settlement_run` columns and status CHECK |
| FR-006, FR-027 | `content_fingerprint` UNIQUE |
| FR-006a, FR-007 | `business_date` + `status` lookup before run creation |
| FR-013, FR-016 | `settlement_rejected_record` |
| FR-017–FR-020 | `declared_*` / `observed_*` on `settlement_run`; staged aggregate |
| FR-021, FR-024 | Apply step gated on reconcile outcome; staging holds everything until then |
| FR-025 | Single set-based update in one transaction |
| FR-026 | `records_read` / `records_rejected` / `records_applied` |
| FR-030 | No surrogate id crosses a layer boundary |
| FR-031 | `raw_row_excerpt` masked at write time |
| SC-005 | Atomic apply — no partial state is representable |
| SC-011 | Aggregation is commutative, so outcome is independent of work division |

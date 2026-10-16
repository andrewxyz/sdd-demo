# Quickstart: Validating End-of-Day Transaction Settlement

**Date**: 2026-10-16 | **Plan**: [plan.md](./plan.md)

How to run the feature and prove it works. Scenarios map to the spec's user stories and success
criteria. This is a validation guide — implementation belongs in `tasks.md`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 | Constitution: Technology Stack |
| Maven 3.9+ | |
| Docker | For Testcontainers-backed integration tests |
| PostgreSQL 16 | Local or containerised — see [research R1](./research.md), pending confirmation |
| A PGP key pair | Test key only; never a production key |

---

## Setup

```bash
# Schema is applied by Liquibase at startup; no manual DDL (Constitution IV)
mvn clean verify

# Local run against a disposable database
scripts/it-db.sh start
scripts/e2e-setup.sh keys        # throwaway PGP keypair, test material only
scripts/e2e-setup.sh seed        # two ACTIVE accounts to settle against
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --ledgersync.landing-zone=./landing \
  --ledgersync.archive=./archive \
  --ledgersync.private-key-path=./target/e2e-keys/private.asc"
```

Once running, the service scans the landing zone every 30 seconds
(`ledgersync.poll.interval`) and processes any delivery whose sentinel has arrived. Drop files
with `scripts/e2e-setup.sh drop <fixture>` and inspect the outcome with
`scripts/e2e-setup.sh check`.

> **If Docker Engine is 29 or newer**, Testcontainers cannot start a container — its bundled
> docker-java negotiates API 1.32 and Docker 29 requires 1.44+. Use the external database instead:
> `scripts/it-db.sh start`, then add `-Dit.jdbc.url=$(scripts/it-db.sh url)` to `mvn verify`.
> On older Docker, plain `mvn verify` works untouched.

Configuration worth knowing before the first run:

| Property | Default | Requirement |
|---|---|---|
| `ledgersync.landing-zone.path` | `./landing` | FR-001 |
| `ledgersync.archive.path` | `./archive` | R10 |
| `ledgersync.chunk-size` | `1000` | Constitution IV |
| `ledgersync.reject-threshold.ratio` | `0.25` | FR-015 |
| `ledgersync.reject-threshold.min-records` | `1000` | FR-015 |
| `ledgersync.delivery-deadline` | `05:00` local | FR-002b |

---

## Scenario 1 — A clean file settles (User Story 1, P1)

**Setup**: seed two accounts with known balances. Build a file per
[contracts/transaction-file.md](./contracts/transaction-file.md) whose header matches its rows.

```bash
# Encrypt and drop the data file, THEN the sentinel — order matters
gpg --encrypt --recipient ledgersync-test --output landing/txn-20261016-01.csv.pgp fixture-clean.csv
touch landing/txn-20261016-01.csv.pgp.done
```

**Expected**:

- Processing starts with no manual trigger, only after the sentinel appears.
- Each account's balance equals its opening balance plus its net delta.
- `settlement_run.status` is `SETTLED`, with `records_read` = `records_applied`,
  `records_rejected` = 0.
- No alert is emitted.
- Every log line is JSON and carries the same `executionId`.

**Proves**: FR-001, FR-002, FR-024, FR-026, SC-001.

---

## Scenario 2 — An unbalanced file is refused (User Story 2, P2)

**Setup**: take the clean fixture and alter the header's net total so it disagrees with the rows.

**Expected**:

- No customer balance changes — verify by comparing every balance before and after.
- `settlement_run.status` is `FAILED` with a populated `failure_reason`.
- One `RECONCILIATION_MISMATCH` alert, carrying declared and observed count and net total.
- The alert contains no account number and no surrogate database id.

**Proves**: FR-019, FR-021, FR-022, FR-030, SC-006.

> The most valuable assertion here is the negative one: balances unchanged. Assert it over *all*
> seeded accounts, not just the ones the file touched — a partial write would otherwise pass.

---

## Scenario 3 — Bad rows are reported in full, and the file still fails (User Story 3, P3)

**Setup**: a 1,000-row file with 7 defects of differing kinds, deliberately spread across the file
(near the start, middle, and last row).

**Expected**:

- Reading does not stop at the first defect; all 7 reach `settlement_rejected_record`.
- Each rejected row names its failing field and carries a plain-language reason.
- Account numbers in `raw_row_excerpt` are masked to the last four characters.
- `settlement_run.status` is `FAILED` — a file with rejects cannot reconcile (clarification Q1).
- No balance changed.

**Proves**: FR-013, FR-014, FR-020, FR-031, SC-003, SC-004.

> Placing a defect on the **last** row is the assertion that matters. If reading stopped early, that
> row never gets reported, and the test catches it.

---

## Scenario 4 — A hopeless file is abandoned early (FR-015)

**Setup**: a 5,000-row file in which ~40% of rows are malformed — simulating the wrong file landing
in the zone.

**Expected**:

- Reading stops after the threshold trips, not at row 5,000.
- `settlement_run.early_stopped` is `true`.
- An `EARLY_STOP_THRESHOLD` alert with `readAbandoned: true` and a `recordsRead` below the file's
  true row count.

> **Observed against the live app (chunk size 1,000): this does not yet happen as written.** The
> run does stop and is `FAILED`, but `early_stopped` stays `false`, the alert is a generic
> `RECONCILIATION_MISMATCH` reading `Skip limit of '0' exceeded`, and no rejected rows are recorded.
> The integration test passes only because it uses chunk size 100. See the
> [README known issues](../../README.md#9-known-issues-and-honest-gaps), item 1.

**Also test the floor**: a 40-row file with 30 rejects must *not* trip early (it never reaches
1,000 records read) and must report all 30.

**Proves**: FR-015, FR-015a.

---

## Scenario 5 — Redelivery and corrected files (User Story 4, P4)

Run in sequence against one business date:

1. Submit an unbalanced file → `FAILED`.
2. Submit the **byte-identical** file again → ignored by fingerprint, no new run created.
3. Submit a corrected file (new `<seq>`) → `SETTLED`.
4. Submit a *different* file for that now-settled date → held for operator, not settled.

**Expected**: both the failed and the successful run remain queryable, each with its own
`executionId`.

**Proves**: FR-006, FR-006a, FR-007, FR-027, SC-008, SC-009.

---

## Scenario 6 — Atomicity under interruption (SC-005)

**Setup**: a valid 50,000-row file. Kill the process during the apply step.

**Expected**: balances are either fully updated or wholly untouched — never partial. On restart,
staging resumes from the last committed chunk and apply re-runs in full
([research R6](./research.md)).

**Proves**: FR-025, FR-028, SC-005.

> This is the scenario most likely to be skipped and most costly to get wrong. Automate it with a
> transaction-level fault injection rather than relying on manual process kills.

---

## Scenario 7 — Missing delivery is noticed (FR-002b)

**Setup**: drop a data file **without** its sentinel and let the deadline pass. Separately, drop
nothing at all.

**Expected**: both produce a `DELIVERY_OVERDUE` alert. Silence is never treated as success.

**Proves**: FR-002b, SC-010.

---

## Test commands

```bash
# Unit: validator, reconciliation, skip policy, masking, MDC, ArchUnit — no Spring, no database
mvn test

# Integration: full job runs against real PostgreSQL
mvn verify -DexcludedGroups=slow
# ...or, on Docker 29+, against an externally started database:
scripts/it-db.sh start
mvn verify -DexcludedGroups=slow -Dit.jdbc.url=$(scripts/it-db.sh url)

# Throughput (SC-001, 500,000 rows) — nightly, not per pull request
mvn verify -Dgroups=slow
```

Unit tests for `RecordValidator`, `ReconciliationService`, and `ThresholdSkipPolicy` must run
without a database or Spring context — that is a constitutional gate, not a preference, and it is
worth asserting in CI that this module's unit test phase needs no Docker.

---

## Readiness checklist

- [ ] Scenario 1 settles and no alert fires
- [ ] Scenario 2 leaves every balance untouched
- [ ] Scenario 3 reports the last-row defect
- [ ] Scenario 4 trips the ratio and respects the floor
- [ ] Scenario 5 distinguishes redelivery from correction
- [ ] Scenario 6 shows no partial state under interruption
- [ ] Scenario 7 alerts on silence
- [ ] All log output is JSON with a consistent `executionId`
- [ ] No alert or log line contains an account number or surrogate id

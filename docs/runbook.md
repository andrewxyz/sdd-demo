# LedgerSync-Batch Operational Runbook

End-of-day transaction settlement. Written for the on-call engineer and Settlement Operations.

---

## What this service does

Each night a partner institution drops an encrypted transaction file into the landing zone. The
service reads it, checks every row, proves the file balances against its own declared header, and
only then moves customer balances.

**The rule that surprises people most:** a file containing *any* invalid row will not settle. The
run reads to the end so you get the complete list of defects, then fails. Once a row is dropped,
the file's control total can no longer prove nothing was lost, so settling anyway would mean
knowingly booking an incomplete day.

---

## Running the service

```bash
scripts/it-db.sh start          # PostgreSQL 16 on port 55432
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --ledgersync.landing-zone=/path/to/landing \
  --ledgersync.archive=/path/to/archive \
  --ledgersync.private-key-path=/path/to/private.asc"
```

The service polls the landing zone every `ledgersync.poll.interval` (default 30 seconds) and
processes any delivery whose sentinel has arrived. Set `ledgersync.poll.enabled=false` to stop it
scanning — useful when draining before a deployment.

Nothing happens on startup by design: the job is triggered by sentinel arrival, never on boot.
Seeing no activity in the log is normal when the landing zone is empty.

For a full local exercise including PGP encryption, use `scripts/e2e-setup.sh` — run it with no
arguments for the fixture list.

---

## Landing zone layout

| Item | Pattern | Example |
|---|---|---|
| Data file | `txn-<YYYYMMDD>-<seq>.csv.pgp` | `txn-20261016-01.csv.pgp` |
| Sentinel | `<data file>.done` | `txn-20261016-01.csv.pgp.done` |

The **sentinel is the trigger**. A data file without one is never read, however long it has been
sitting there. That is deliberate — it is what stops a mid-upload file being read as complete.

`<seq>` increments for a corrected resend on the same date (`-02`, `-03`).

### Directories

| Path | Configured by | Contents |
|---|---|---|
| Landing zone | `ledgersync.landing-zone` | Pending deliveries |
| Archive | `ledgersync.archive` | `<archive>/<business-date>/` — processed files, kept for audit |

Processed files are archived on **any** terminal outcome, success or failure. The decrypted
temporary file is always deleted, including on the failure path.

---

## Run statuses

| Status | Meaning |
|---|---|
| `PENDING` | Row created, work not started |
| `RUNNING` | In progress |
| `SETTLED` | Reconciled and balances applied |
| `FAILED` | Nothing was applied; no balance moved |

There is no transition out of `SETTLED` or `FAILED`. A corrected file creates a **new** run; the
failed one is retained.

---

## Alerts and what to do about them

| Alert | What happened | First action |
|---|---|---|
| `DELIVERY_OVERDUE` | No settled file for a business date by the deadline | Check the landing zone for a data file with no sentinel, then chase the producer |
| `BROKEN_DELIVERY` | A sentinel names a missing or unreadable data file | Producer-side problem; ask for a resend. Waiting will not help |
| `DECRYPTION_FAILED` | The file could not be opened | Check key material and whether the producer changed recipient keys |
| `HEADER_UNREADABLE` | Header missing or malformed | Producer-side; the file cannot be validated at all |
| `RECONCILIATION_MISMATCH` | Rows disagree with the declared header | Read `rejectedRecordCount` first — see below |
| `EARLY_STOP_THRESHOLD` | Over 25% of rows rejected after 1,000 read | Almost always the wrong file or wrong format. **The rejection list is partial.** ⚠️ *Currently this alert type is never actually sent.* See the note below |

> **Known issue — abandoned files.** When a file is over 25% bad, the run is correctly `FAILED`, but
> today it surfaces as a generic `RECONCILIATION_MISMATCH` alert whose message reads
> `Skip limit of '0' exceeded`, `early_stopped` stays `false`, and at the production chunk size no
> rejected rows are recorded. Treat that message as "this file is mostly bad, probably the wrong
> file". Details in the [README](../README.md#9-known-issues-and-honest-gaps), item 1.

> **Known issue — bad encryption.** `DECRYPTION_FAILED` is only raised when a file was encrypted to
> the *wrong key*. A plain CSV or a password-encrypted file instead appears as a
> `RECONCILIATION_MISMATCH` whose message mentions `null` or `cast`. If you see that, the file was
> not encrypted to the app's public key; the totals are not the problem. README item 14.
| `SETTLED_DATE_CONFLICT` | A new file arrived for a date already settled | Do not settle it. Confirm with the producer which file is authoritative |

### Reading a `RECONCILIATION_MISMATCH`

`rejectedRecordCount` tells you which kind of problem you have:

- **`rejectedRecordCount` is 0** — the file is internally well-formed but its totals disagree with
  its header. This is a transit or generation problem at the producer.
- **`rejectedRecordCount` is above 0** — individual rows failed validation. Fix those and the
  totals usually follow.

`readAbandoned: true` on any alert means the rejection list covers only the first `recordsRead`
rows. Do not treat it as the complete set of defects.

---

## Finding out why a row was rejected (SC-007)

Every alert carries an `executionId`. Use it:

```sql
-- Why did rows fail in this run?
SELECT source_row_number, failing_field, rejection_reason, rejected_at
  FROM settlement_rejected_record
 WHERE execution_id = '<executionId from the alert>'
 ORDER BY source_row_number;
```

By business date, when you do not have the execution id to hand:

```sql
SELECT r.source_file_name, e.source_row_number, e.failing_field, e.rejection_reason
  FROM settlement_rejected_record e
  JOIN settlement_run r ON r.execution_id = e.execution_id
 WHERE r.business_date = DATE '2026-10-16'
 ORDER BY e.source_row_number;
```

Run summary:

```sql
SELECT execution_id, source_file_name, status, records_read, records_rejected,
       records_applied, declared_count, observed_count,
       declared_net_total, observed_net_total, failure_reason, early_stopped
  FROM settlement_run
 WHERE business_date = DATE '2026-10-16'
 ORDER BY started_at;
```

**Account numbers in `raw_row_excerpt` are masked to their last four characters.** That is
deliberate and not a bug — the plaintext never reaches storage. To identify the account, use
`source_row_number` against the archived source file.

---

## Reprocessing a failed date

1. Confirm the date is `FAILED`, not `SETTLED` — the service will not auto-settle over a settled
   date.
2. Ask the producer for a corrected file with the **next** `<seq>`.
3. Drop the data file, then the sentinel. Processing starts automatically.
4. The failed run stays on record. Both runs remain queryable by their own execution ids.

A **byte-identical** redelivery is recognised by content fingerprint and ignored — no new run is
created and no balance moves. This is safe to do.

---

## Things that are working as designed

- **A file with 1 bad row out of 10,000 does not settle.** See the top of this document.
- **Runs are strictly serial.** A second file waits its turn, and earlier business dates settle
  first. A long run delays the next file; that is expected.
- **A failed run leaves balances completely untouched** — never partially updated. If you see a
  `FAILED` run, you do not need to check for partial application.
- **No alert contains account numbers or amounts from individual rows.** Use the execution id to
  query detail.

---

## Configuration

| Property | Default | Notes |
|---|---|---|
| `ledgersync.chunk-size` | `1000` | Fixed by the constitution; change needs justification |
| `ledgersync.reject-threshold.ratio` | `0.25` | Above this, reading is abandoned |
| `ledgersync.reject-threshold.min-records` | `1000` | Floor before the ratio applies |
| `ledgersync.delivery-deadline` | `03:00` | Must precede the cutoff by run duration + resend window, or startup fails |
| `ledgersync.operational-cutoff` | `06:00` | The operational deadline in SC-002 |
| `ledgersync.retention.staged-days` | `30` | Staged rows for terminal runs |
| `ledgersync.retention.audit-days` | `2555` | Runs and rejected records (~7 years) |

If the service refuses to start with an `FR-002c` message, the delivery deadline has been set too
close to the cutoff to leave room to act. Move the deadline earlier.

# Phase 0 Research: End-of-Day Transaction Settlement

**Date**: 2026-10-16 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Resolves every NEEDS CLARIFICATION in the plan's Technical Context, plus the two items the
clarification session left open (restart granularity, file disposition) and the design questions
the constitution's principles force.

---

## R1. Relational database engine

**Decision**: PostgreSQL 16. **Confirmed by the user on 2026-10-16** — no longer provisional.

**Rationale**: The constitution mandates Liquibase but names no engine, so one must be chosen to
proceed. PostgreSQL carries the three properties this design actually leans on: exact `NUMERIC`
arithmetic with no binary floating-point drift (money must not drift), `UPDATE ... FROM` for the
set-based balance apply in R3, and transactional DDL so a Liquibase changeset that fails rolls back
cleanly rather than leaving a half-migrated schema.

**Alternatives considered**: *Oracle* — common in banking and equally capable, but licensing is an
organisational decision, not a technical one. *MySQL/MariaDB* — workable, but DDL is not
transactional, so a failed migration can strand the schema. *H2* — test-only; using it in
production would be a correctness risk given money arithmetic.

**Status**: Confirmed. All downstream work — the Liquibase changelogs, the `NUMERIC(19,4)` money
columns, and the `UPDATE ... FROM` apply statement in R3 — binds to PostgreSQL semantics.

---

## R2. Why reading and applying must be separate steps

**Decision**: Three-step job — `stageTransactions` (chunked) → `reconcile` (tasklet) →
`applyBalances` (tasklet). Staged rows live in `settlement_staged_transaction`, keyed by execution.

**Rationale**: Clarification Q1 settled that a file must balance against its *full* header before
anything is applied. A single-pass chunked job cannot honour that: by the time chunk 500 reveals
the file does not balance, chunks 1–499 have already been committed to balances. Staging decouples
"what did the file say" from "what shall we do about it", and it is what makes FR-021's promise —
mismatch leaves every balance unchanged — structurally true rather than merely intended.

The staging table also pays for itself twice over: it is the aggregate source for R3, and it gives
the restart semantics in R6 somewhere to resume from.

**Alternatives considered**: *Two passes over the file* (count/sum, then re-read and apply) — avoids
the staging table but reads 500,000 rows twice, and nothing guarantees the file is byte-identical
between passes. *In-memory accumulation* — 500,000 records held across a job is a heap risk, and it
is lost entirely on restart.

---

## R3. Applying balances atomically at 500,000-row scale

**Decision**: Aggregate staged rows to one net delta per account, then apply them with a single
set-based `UPDATE customer_balance ... FROM (SELECT account_number, SUM(signed_amount) ...)` inside
one transaction.

**Rationale**: FR-025 demands all-or-nothing. The naive reading — one transaction around 500,000
individual updates — is the kind of transaction that times out or exhausts rollback segments. But
the number of *accounts* touched is far smaller than the number of transactions; aggregation
collapses the write to roughly the distinct-account count, which a single transaction handles
comfortably.

This also quietly dissolves FR-025b. Ordering within an account only matters if the operations are
order-sensitive, and summing deltas is commutative. The spec's ordering requirement is satisfied by
construction, *provided* no order-sensitive rule (such as an overdraft check) is introduced into the
apply path — and the spec places overdraft policy out of scope. **This is a constraint to preserve:
if overdraft enforcement is ever brought in-scope, aggregation stops being safe and this decision
must be revisited.**

**Alternatives considered**: *Chunked writer with per-chunk commit* — violates FR-025 outright.
*Chunked writer inside one outer transaction* — atomic but holds locks for the full write with none
of chunking's benefit. *Compensating reversal on failure* — the reversal must itself be atomic, so
the problem recurses; a failed compensation corrupts the ledger.

---

## R4. File decryption

**Decision**: PGP (OpenPGP) decryption via Bouncy Castle, with the private key supplied through the
existing key-management arrangement at runtime; never from the repository.

**Rationale**: PGP is the prevailing convention for partner file exchange in financial messaging and
is what an external institution is most likely to already support. The spec puts key distribution
out of scope, so the design needs only a key-material port that the deployment satisfies.
Decryption streams to a temporary location rather than into memory, since a 500,000-row file should
never be fully buffered.

**Alternatives considered**: *AES with a shared secret* — simpler, but symmetric key exchange with an
external party is operationally worse. *Transport-only encryption (SFTP)* — leaves the file at rest
in plaintext in the landing zone, which fails Principle I for financial data.

**Follow-up**: the decrypted temporary file must be deleted in a `finally` path regardless of
outcome — see R10.

---

## R5. Implementing the 25%-after-1000 skip threshold

**Decision**: A custom `ThresholdSkipPolicy implements SkipPolicy`, reading skip count and read
count from `StepExecution`, returning `true` while `readCount < 1000`, and thereafter while
`skipCount / readCount <= 0.25`. Both figures bind from configuration.

**Rationale**: Spring Batch's built-in skip limit is an absolute count only, so it cannot express
FR-015's percentage-with-a-floor rule. A custom policy is the framework's own extension point for
exactly this, so it is not a workaround. Constitution Principle IV requires the limit to be explicit
and bounded — this policy is both, and it is unit-testable with no Spring context, which the
Development Workflow gate requires.

**Alternatives considered**: *Generous absolute `skipLimit`* — cannot express the ratio, and the right
absolute number depends on file size. *Post-hoc check after the step* — the point of the threshold is
to stop reading early; checking afterwards wastes exactly the window it was meant to save.

---

## R6. Restart granularity *(deferred from clarification)*

**Decision**: Restart resumes at the staging step's last committed chunk, using Spring Batch's own
`JobRepository` state and the file's content fingerprint as the restart identity. The reconcile and
apply steps are never partially complete, so they simply re-run.

**Rationale**: This is the question `/speckit-clarify` deferred to the plan, and the three-step split
answers it almost for free. Each step has different resumption characteristics, and only one needs
thought:

- **stage** — chunked and therefore interruptible; Spring Batch records the last committed chunk, so
  resuming re-reads only the remainder. Staged rows from the failed attempt are keyed by execution
  and cleaned on restart to prevent double-counting.
- **reconcile** — pure arithmetic over staged data; re-running costs one aggregate query.
- **apply** — a single transaction, so it either completed or did nothing. There is no "half" to
  resume.

FR-028's requirement that restart never double-applies therefore holds because apply is atomic, not
because restart is clever.

**Alternatives considered**: *Restart the whole file from the top* — simpler, but discards up to 30
minutes of work and risks missing the 06:00 cutoff (SC-002). *Row-level resumption* — finer than the
chunk boundary, unsupported by the framework's commit model, and buys nothing once apply is atomic.

---

## R7. Serial execution and business-date ordering

**Decision**: A single-threaded `JobLauncher` (synchronous `TaskExecutor`), plus a database advisory
lock held for the duration of a run so that two application instances cannot both launch. Queued
files are selected in business-date order.

**Rationale**: FR-025a requires strictly serial runs and FR-025c requires earlier dates to settle
first. A synchronous launcher gives serialisation within one JVM; the advisory lock extends that
guarantee across instances, which matters the moment the service is deployed more than once — and a
batch service that silently double-runs during a rolling restart is a financial incident.

**Alternatives considered**: *Rely on the scheduler to never overlap* — an assumption, not a control,
and it fails under manual re-trigger. *`JobRepository` uniqueness on job parameters* — prevents an
identical re-run, but does nothing to stop two *different* files running concurrently.

---

## R8. Content fingerprint

**Decision**: SHA-256 over the *encrypted* file bytes, computed streaming during the delivery-
eligibility check, stored on `settlement_run`, and uniquely indexed.

**Rationale**: FR-006 needs redelivery to be recognisable. Fingerprinting the encrypted bytes means
no decryption is needed to detect a duplicate, so a redelivered file costs almost nothing. A unique
index makes the guarantee the database's job rather than the application's, which closes the race
between two near-simultaneous deliveries.

**Alternatives considered**: *Fingerprint the decrypted content* — tolerates re-encryption producing
different bytes, but requires decrypting every duplicate before recognising it. *File name plus
size* — collides too easily to anchor a financial idempotency rule. *MD5* — faster, but a
collision-prone digest guarding "have these transactions already been applied" is not a trade worth
making.

---

## R9. Execution ID, MDC, and JSON logging

**Decision**: Generate a UUID in a `JobExecutionListener.beforeJob`, store it on `settlement_run`,
bind it to MDC there, and clear it in `afterJob`. Add a `ChunkListener` that re-binds MDC before
each chunk and clears after, so chunk-scoped threads inherit the context. Logging via
`logback-spring.xml` with a JSON encoder.

**Rationale**: Constitution Principle V requires propagation through every layer *including*
chunk-scoped threads, and explicit clearing to prevent bleed. MDC is thread-local, so a listener at
job level alone leaks context on pooled threads — the chunk-level re-bind is what makes the
guarantee real rather than nominal.

**Alternatives considered**: *Pass the execution ID as a method parameter throughout* — explicit, but
pollutes every signature in the reconciliation layer, which Principle II wants kept free of
framework concerns. *Spring Batch's native `jobExecutionId`* — a database sequence value, so using it
as the public correlation identifier would expose an internal key, violating Principle I and FR-030.

---

## R10. File disposition after processing *(outstanding item from clarification)*

**Decision**: On any terminal outcome, move the encrypted source file and its sentinel to a dated
archive directory; delete the decrypted temporary file in a `finally` block unconditionally.
Archive retention follows the organisation's financial record standard and is enforced outside the
application.

**Rationale**: The spec flagged this as outstanding. Leaving processed files in the landing zone
makes the zone's contents ambiguous and invites reprocessing confusion; deleting the source
destroys evidence a reconciliation dispute would need. Archiving keeps both properties. The
decrypted temporary file is the sharp edge — plaintext financial data at rest — so its removal must
not depend on the happy path.

**Alternatives considered**: *Delete the source after settlement* — unacceptable; the file is audit
evidence. *Leave files in place and track processed state in the database* — the landing zone grows
without bound and operators lose the ability to tell at a glance what is pending.

---

## R11. Alert delivery

**Decision**: An `SettlementAlertPublisher` port with a single outbound payload shape (see
[contracts/alert-hook.md](./contracts/alert-hook.md)). Alert dispatch happens **outside** the
settlement transaction, after commit or rollback is decided.

**Rationale**: The spec puts routing and escalation out of scope, so the design needs a seam, not a
channel. Dispatching inside the transaction is the subtle trap: a rollback would silently discard
the alert about the very failure that caused it — the run fails *and* nobody is told. Publishing
after the outcome is settled avoids that. An alert that itself fails to send is logged at error
level and does not change the run's terminal status, since the ledger outcome is already decided.

**Alternatives considered**: *Direct email/webhook call in the failure handler* — couples the domain to
a channel the spec excluded. *Transactional outbox* — stronger delivery guarantee and a reasonable
future step, but it is infrastructure this feature does not yet need.

---

## Resolved unknowns summary

| Unknown | Source | Resolution |
|---|---|---|
| Database engine | Technical Context | PostgreSQL 16 — **confirmed 2026-10-16** |
| Restart granularity | Clarify, deferred | Resume at last committed stage chunk; apply is atomic |
| File disposition | Clarify, outstanding | Archive source + sentinel; always delete decrypted temp |
| Decryption mechanism | FR-003 | PGP via Bouncy Castle, streamed |
| Threshold implementation | FR-015 | Custom `ThresholdSkipPolicy` |
| Atomic apply at scale | FR-025 | Aggregate per account, single set-based update |
| Serial execution | FR-025a/c | Synchronous launcher + advisory lock; date-ordered queue |
| Fingerprint | FR-006 | Streaming SHA-256 of encrypted bytes, unique index |
| Execution ID propagation | Constitution V | UUID in MDC via job + chunk listeners |
| Alert seam | FR-021, FR-022 | Publisher port, dispatched outside the transaction |

**All unknowns are resolved. No decision remains outstanding before implementation.**

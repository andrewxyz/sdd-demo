---

description: "Task list for End-of-Day Transaction Settlement"
---

# Tasks: End-of-Day Transaction Settlement

**Input**: Design documents from `/specs/001-eod-transaction-settlement/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: Test tasks are **included and mandatory**. The constitution's Development Workflow
section requires that reconciliation and business logic carry unit tests runnable without a
database or Spring context, and that each `Job` have at least one integration test for a successful
run and one for the skip/retry path. These are governance requirements, not the template's optional
default.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

> ✅ **Database confirmed**: PostgreSQL 16, settled 2026-10-16 ([research R1](./research.md)).
> No decisions remain open — Phase 1 can start.
>
> 🔄 **Revised 2026-10-16** after `/speckit-analyze`. Task IDs were renumbered; all 16 findings are
> addressed. See the Analysis Remediation table at the end of this file for the mapping.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

Single-module Maven project per [plan.md](./plan.md): `src/main/java/com/ledgersync/batch/`,
`src/main/resources/`, `src/test/java/com/ledgersync/batch/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [ ] T001 Create Maven project skeleton and package directories (`config`, `ingestion`, `reconciliation`, `persistence`, `alerting`, `observability`) under src/main/java/com/ledgersync/batch/ per plan.md Project Structure
- [ ] T002 Configure pom.xml with Java 21, Spring Boot 3.x parent, spring-boot-starter-batch, spring-boot-starter-data-jpa, liquibase-core, postgresql driver, bouncycastle (bcpg-jdk18on), and centrally managed dependency versions in a `<dependencyManagement>` block per Constitution Technology Stack
- [ ] T003 [P] Add test dependencies to pom.xml: junit-jupiter, spring-batch-test, assertj-core, testcontainers-postgresql, archunit-junit5
- [ ] T004 [P] Create src/main/java/com/ledgersync/batch/LedgerSyncBatchApplication.java as the Spring Boot entry point
- [ ] T005 [P] Create src/main/resources/application.yml with `spring.jpa.hibernate.ddl-auto: validate` (Constitution IV forbids any other value), datasource config, and Liquibase enablement
- [ ] T006 [P] Create src/main/java/com/ledgersync/batch/config/SettlementProperties.java as a `@ConfigurationProperties` record exposing landing-zone path, archive path, chunk size (default 1000), reject-threshold ratio (default 0.25), reject-threshold min-records (default 1000), delivery deadline, and operational cutoff — with a startup validation that the deadline precedes the cutoff by at least the expected run duration plus one resend window, per FR-002c
- [ ] T007 [P] Create src/main/resources/logback-spring.xml configuring a JSON encoder so all log output is structured JSON per Constitution V
- [ ] T008 [P] Create docker-compose.yml at repository root with a PostgreSQL 16 service for local runs and integration tests
- [ ] T009 [P] Create .github/pull_request_template.md requiring every PR to state which constitution principles the change touches and how compliance was verified, per Constitution Development Workflow

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared spine every user story needs — schema, batch infrastructure, ingestion
plumbing, and the constitutional guardrails

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Schema

- [ ] T010 Create src/main/resources/db/changelog/db.changelog-master.yaml including the numbered changelogs in order
- [ ] T011 [P] Create src/main/resources/db/changelog/001-settlement-run.yaml creating `settlement_run` per data-model.md §1 with `execution_id` UUID NOT NULL UNIQUE, `source_file_name` VARCHAR(512) NOT NULL, `content_fingerprint` VARCHAR(64) NOT NULL UNIQUE, `business_date` DATE NOT NULL indexed, `status` VARCHAR(16) NOT NULL CHECK in (PENDING, RUNNING, FAILED, SETTLED), `started_at` TIMESTAMPTZ NOT NULL, `completed_at` TIMESTAMPTZ NULL, `records_read`/`records_rejected`/`records_applied` BIGINT NOT NULL DEFAULT 0, `declared_count` BIGINT NULL, `declared_net_total` NUMERIC(19,4) NULL, `observed_count` BIGINT NULL, `observed_net_total` NUMERIC(19,4) NULL, `failure_reason` VARCHAR(256) NULL, `early_stopped` BOOLEAN NOT NULL DEFAULT false — **and in the same changeset** a CHECK constraint enforcing that `status = 'SETTLED'` requires `records_rejected = 0` AND `observed_count = declared_count` AND `observed_net_total = declared_net_total`, encoding clarification Q1 at the database level. One changeset, because Constitution IV makes changesets immutable once merged
- [ ] T012 [P] Create src/main/resources/db/changelog/002-staged-transaction.yaml creating `settlement_staged_transaction` per data-model.md §2 with `execution_id` UUID NOT NULL FK, `source_row_number` BIGINT NOT NULL, `account_number` VARCHAR(34) NOT NULL, `transaction_type` VARCHAR(16) NOT NULL, `amount` NUMERIC(19,4) NOT NULL, `signed_amount` NUMERIC(19,4) NOT NULL, `transaction_timestamp` TIMESTAMPTZ NOT NULL, and a composite index on `(execution_id, account_number)`
- [ ] T013 [P] Create src/main/resources/db/changelog/004-customer-balance.yaml creating `customer_balance` with `account_number` VARCHAR(34) PK, `balance` NUMERIC(19,4) NOT NULL, `last_settled_at` TIMESTAMPTZ NULL, `status` VARCHAR(16) NOT NULL — no binary floating-point type anywhere per data-model.md conventions

### Persistence layer

- [ ] T014 [P] Create JPA entities SettlementRun.java, StagedTransaction.java, and CustomerBalance.java in src/main/java/com/ledgersync/batch/persistence/entity/ as mutable classes — records MUST NOT be used as entities per Constitution III
- [ ] T015 [P] Create SettlementRunRepository.java, StagedTransactionRepository.java, and CustomerBalanceRepository.java in src/main/java/com/ledgersync/batch/persistence/repository/ with read methods annotated `@Transactional(readOnly = true)` per Constitution I

### Batch infrastructure

- [ ] T016 Create src/main/java/com/ledgersync/batch/config/BatchInfrastructureConfig.java defining the `JobRepository` and a synchronous single-threaded `JobLauncher` so runs are strictly serial per FR-025a and research R7
- [ ] T017 Implement a database advisory lock held for the duration of a run in src/main/java/com/ledgersync/batch/config/BatchInfrastructureConfig.java so two application instances cannot launch concurrently per FR-025a and research R7

### Observability

- [ ] T018 [P] Create src/main/java/com/ledgersync/batch/observability/ExecutionIdJobListener.java generating a UUID in `beforeJob`, binding it to MDC, and clearing MDC in `afterJob` per FR-004 and Constitution V — the UUID MUST NOT be the `JobRepository` sequence value (FR-030)
- [ ] T019 [P] Create src/main/java/com/ledgersync/batch/observability/SettlementChunkListener.java re-binding the execution id to MDC before each chunk and clearing it after, so chunk-scoped threads inherit context per research R9

### Ingestion plumbing

- [ ] T020 Create src/main/java/com/ledgersync/batch/ingestion/LandingZoneScanner.java detecting `<data file>.done` sentinels and refusing to read any data file lacking one, per FR-001, FR-002 and contracts/transaction-file.md §1
- [ ] T021 Create src/main/java/com/ledgersync/batch/ingestion/FileDecryptor.java performing streaming OpenPGP decryption to a temporary file, deleting the temporary file in a `finally` block regardless of outcome, per FR-003 and research R4/R10
- [ ] T022 Create src/main/java/com/ledgersync/batch/ingestion/ContentFingerprinter.java computing a streaming SHA-256 over the **encrypted** file bytes per FR-006 and research R8

### Alerting seam

- [ ] T023 [P] Create src/main/java/com/ledgersync/batch/alerting/SettlementAlertPublisher.java as a port interface, and AlertPayload.java as a record in src/main/java/com/ledgersync/batch/alerting/dto/ with the fields in contracts/alert-hook.md §2

### Constitutional guardrails

- [ ] T024 [P] Create src/test/java/com/ledgersync/batch/arch/ConstitutionRulesTest.java using ArchUnit to fail the build on: any field-level `@Autowired` (Constitution III), any Java `record` annotated `@Entity` (Constitution III), any `ingestion`→`persistence` or reversed layer dependency (Constitution II), and any public type in `reconciliation` importing a Spring Batch or JPA type (Constitution II). This is a **standing gate** that grows teeth as code lands; it passes trivially while packages are empty

**Checkpoint**: Foundation ready — user story implementation can now begin

---

## Phase 3: User Story 1 - Settle a clean daily transaction file (Priority: P1) 🎯 MVP

**Goal**: A well-formed file whose header matches its rows settles end to end with no human
involvement, leaving every affected balance correct and the run marked SETTLED.

**Independent Test**: Drop a valid file plus sentinel into the landing zone; verify each account
balance equals opening balance plus its net delta, `settlement_run.status` is `SETTLED`, and no
alert fired. See [quickstart.md](./quickstart.md) Scenario 1.

### Tests for User Story 1

> **Write these tests FIRST and ensure they FAIL before implementation**

- [ ] T025 [P] [US1] Create clean-file fixtures (header matching rows, and a zero-record file) in src/test/resources/fixtures/ per contracts/transaction-file.md §3
- [ ] T026 [P] [US1] Unit test FileHeaderParser in src/test/java/com/ledgersync/batch/unit/FileHeaderParserTest.java covering FR-017 header field extraction — no Spring context, no database
- [ ] T027 [P] [US1] Unit test ReconciliationService in src/test/java/com/ledgersync/batch/unit/ReconciliationServiceTest.java asserting FR-018 signed-sum arithmetic at `NUMERIC(19,4)` precision and FR-010 rejection of amounts with more than 2 decimal places — no Spring context, no database
- [ ] T028 [P] [US1] Integration test in src/test/java/com/ledgersync/batch/integration/CleanFileSettlementIT.java asserting FR-029 SETTLED status, correct balances, `records_read == records_applied`, `records_rejected == 0`, and that no alert was published

### Implementation for User Story 1

- [ ] T029 [P] [US1] Create records TransactionRecord.java and FileHeader.java in src/main/java/com/ledgersync/batch/ingestion/dto/ per data-model.md §5
- [ ] T030 [P] [US1] Create record ReconciliationResult.java in src/main/java/com/ledgersync/batch/reconciliation/dto/ carrying declared and observed count and net total plus rejected count — not a bare boolean, because FR-022 needs all four figures
- [ ] T031 [US1] Implement src/main/java/com/ledgersync/batch/ingestion/FileHeaderParser.java parsing the `H,<business_date>,<declared_record_count>,<declared_net_total>` row per FR-017 and contracts/transaction-file.md §3
- [ ] T032 [US1] Implement src/main/java/com/ledgersync/batch/ingestion/TransactionFileReader.java and TransactionRecordMapper.java mapping `D` rows to TransactionRecord, deriving `signed_amount` as `+amount` for CREDIT and `-amount` for DEBIT per FR-018 and contracts/transaction-file.md §3
- [ ] T033 [US1] Implement src/main/java/com/ledgersync/batch/persistence/StagedTransactionWriter.java as an `ItemWriter` persisting staged rows inside an explicit `@Transactional` boundary with declared propagation per Constitution I
- [ ] T034 [US1] Implement src/main/java/com/ledgersync/batch/reconciliation/ReconciliationService.java computing observed count and signed sum from staged rows and comparing against the header per FR-018 and FR-019 — pure arithmetic, no I/O, no framework types per Constitution II
- [ ] T035 [US1] Implement src/main/java/com/ledgersync/batch/persistence/BalanceApplier.java performing the single set-based `UPDATE customer_balance ... FROM (SELECT account_number, SUM(signed_amount) ... GROUP BY account_number)` inside one `@Transactional` boundary per data-model.md §4 and research R3 — one statement, one transaction, one row per account
- [ ] T036 [US1] Create src/main/java/com/ledgersync/batch/config/SettlementJobConfig.java wiring three steps: chunked `stageTransactions` at the configured chunk size of 1000, `reconcile` tasklet, and `applyBalances` tasklet gated on reconciliation success per FR-019, FR-024 and research R2
- [ ] T037 [US1] Implement run status transitions and count persistence (`records_read`, `records_applied`, `completed_at`, `SETTLED`) in src/main/java/com/ledgersync/batch/persistence/ per FR-005, FR-026 and FR-029
- [ ] T038 [US1] Implement archiving of the encrypted source file and its sentinel to the dated archive directory on terminal outcome in src/main/java/com/ledgersync/batch/ingestion/ per research R10

**Checkpoint**: A clean file settles end to end. This is the MVP — stop and validate here.

---

## Phase 4: User Story 2 - Block settlement when the ledger does not balance (Priority: P2)

**Goal**: A file that disagrees with its own header is refused, no balance moves, and an urgent
alert names expected versus actual. This phase also lands every other alert path, so that after it
no failure mode is silent.

**Independent Test**: Submit a file whose header net total differs from its rows; verify every
seeded balance is unchanged, status is `FAILED`, and a `RECONCILIATION_MISMATCH` alert carries all
four figures. See [quickstart.md](./quickstart.md) Scenario 2.

### Tests for User Story 2

- [ ] T039 [P] [US2] Create fixtures for mismatched net total, mismatched record count, missing header, and undecryptable file in src/test/resources/fixtures/
- [ ] T040 [P] [US2] Integration test in src/test/java/com/ledgersync/batch/integration/ReconciliationFailureIT.java asserting **every** seeded balance is unchanged — not only the accounts the file touched, since a partial write would otherwise pass
- [ ] T041 [P] [US2] Test in src/test/java/com/ledgersync/batch/integration/AlertDisclosureIT.java asserting no alert payload contains an account number, an amount from an individual row, or any surrogate database id per FR-030 and contracts/alert-hook.md §3
- [ ] T042 [P] [US2] Test in src/test/java/com/ledgersync/batch/integration/AlertLatencyIT.java asserting an alert is published within 2 minutes of detection per SC-006, using a controllable clock rather than wall-clock sleeps

### Implementation for User Story 2

- [ ] T043 [US2] Implement the mismatch branch in src/main/java/com/ledgersync/batch/reconciliation/ReconciliationService.java marking the run FAILED and leaving balances untouched per FR-020
- [ ] T044 [US2] Persist `failure_reason`, `declared_count`, `declared_net_total`, `observed_count`, and `observed_net_total` on `settlement_run` in src/main/java/com/ledgersync/batch/persistence/ per FR-022
- [ ] T045 [US2] Implement src/main/java/com/ledgersync/batch/alerting/LoggingAlertAdapter.java (or the configured channel adapter) emitting the payload in contracts/alert-hook.md §2 per FR-021
- [ ] T046 [US2] Dispatch alerts **after** the run transaction commits or rolls back, never inside it, in src/main/java/com/ledgersync/batch/alerting/ per research R11 — an alert sent inside a rolled-back transaction is discarded along with it
- [ ] T047 [US2] Implement `HEADER_UNREADABLE` handling marking the run FAILED when the header is absent or unparseable in src/main/java/com/ledgersync/batch/ingestion/FileHeaderParser.java per FR-023
- [ ] T048 [US2] Implement `DECRYPTION_FAILED` handling marking the run FAILED with zero records read in src/main/java/com/ledgersync/batch/ingestion/FileDecryptor.java per FR-003
- [ ] T049 [US2] Implement `BROKEN_DELIVERY` alerting when a sentinel names an absent or unreadable data file in src/main/java/com/ledgersync/batch/ingestion/LandingZoneScanner.java per FR-002a
- [ ] T050 [US2] Implement the missed-delivery deadline check as a scheduled task in src/main/java/com/ledgersync/batch/ingestion/DeliveryDeadlineMonitor.java emitting `DELIVERY_OVERDUE` when no completed delivery exists for a business date by its deadline, per FR-002b, FR-002c and SC-010 — silence must never read as success
- [ ] T051 [US2] Ensure a failed alert dispatch is logged at ERROR and does not change the run's terminal status, in src/main/java/com/ledgersync/batch/alerting/ per contracts/alert-hook.md §4

**Checkpoint**: Unbalanced files are refused, every failure mode alerts, and no alert leaks
customer data. **This plus US1 is the smallest safe production increment.**

---

## Phase 5: User Story 3 - Report every bad row from a single pass (Priority: P3)

**Goal**: Bad rows are set aside with plain-language reasons and reading continues to the end of
the file, so one correction cycle replaces many — while the file still fails, because a file that
lost rows cannot be proven to balance.

**Independent Test**: Submit a 1,000-row file with 7 defects including one on the **last** row;
verify all 7 are recorded, the run is `FAILED`, and no balance moved. See
[quickstart.md](./quickstart.md) Scenario 3.

### Tests for User Story 3

- [ ] T052 [P] [US3] Create fixtures in src/test/resources/fixtures/: 1,000 rows with 7 defects spread across start, middle and **last row**; a 5,000-row file with ~40% malformed; a 40-row file with 30 rejects; a row carrying 3 decimal places
- [ ] T053 [P] [US3] Unit test RecordValidator for every rule in src/test/java/com/ledgersync/batch/unit/RecordValidatorTest.java covering FR-008 through FR-012, including rejection of a 3-decimal-place amount per FR-010 — no Spring context, no database
- [ ] T054 [P] [US3] Unit test ThresholdSkipPolicy in src/test/java/com/ledgersync/batch/unit/ThresholdSkipPolicyTest.java covering: below the 1,000-record floor never trips; above the floor at 25% trips; at exactly 25% does not trip
- [ ] T055 [P] [US3] Integration test in src/test/java/com/ledgersync/batch/integration/RejectedRecordIT.java asserting the **last-row** defect is reported and that all 7 defects surface from a single run per FR-014 and SC-003 — if reading stopped early, that row never appears and this test catches it
- [ ] T056 [P] [US3] Integration test in src/test/java/com/ledgersync/batch/integration/EarlyStopIT.java asserting the 40% file abandons reading with `early_stopped = true`, and the 40-row file does **not** trip and reports all 30
- [ ] T057 [P] [US3] Integration test in src/test/java/com/ledgersync/batch/integration/RejectSurvivesRollbackIT.java forcing a skip that rolls back its chunk and asserting the rejected-record row is still present afterwards per FR-013 and SC-004 — without this, a lost reject row looks identical to a clean chunk

### Implementation for User Story 3

- [ ] T058 [US3] Create src/main/resources/db/changelog/003-rejected-record.yaml creating `settlement_rejected_record` per data-model.md §3 with `execution_id` UUID NOT NULL FK, `source_row_number` BIGINT NOT NULL, `failing_field` VARCHAR(64) NOT NULL, `rejection_reason` VARCHAR(256) NOT NULL, `raw_row_excerpt` VARCHAR(512) NULL, `rejected_at` TIMESTAMPTZ NOT NULL, indexed on `execution_id` — and verify FR-016 query-by-business-date is served by the join to `settlement_run.business_date`, adding a supporting index if the query plan shows otherwise
- [ ] T059 [P] [US3] Create RejectedRecord.java record in src/main/java/com/ledgersync/batch/reconciliation/dto/ and RejectedRecordEntity.java in src/main/java/com/ledgersync/batch/persistence/entity/
- [ ] T060 [US3] Implement src/main/java/com/ledgersync/batch/reconciliation/RecordValidator.java enforcing: `account_number` 1–34 alphanumeric chars and matching a recognised **active** account (FR-009); `amount` numeric, non-negative, at most **2 decimal places** (FR-010); `transaction_type` within the configured contract-bound set CREDIT/DEBIT (FR-011); `timestamp` ISO-8601 parseable and falling within the file's `business_date` (FR-012); all four fields present and well-formed (FR-008) — pure Java, no I/O
- [ ] T061 [US3] Implement src/main/java/com/ledgersync/batch/reconciliation/ValidationProcessor.java as an `ItemProcessor` wrapping RecordValidator per Constitution II
- [ ] T062 [US3] Implement src/main/java/com/ledgersync/batch/reconciliation/ThresholdSkipPolicy.java returning `true` while `readCount < 1000`, and thereafter while `skipCount / readCount <= 0.25`, with both figures bound from SettlementProperties per FR-015 and research R5
- [ ] T063 [US3] Implement src/main/java/com/ledgersync/batch/persistence/RejectedRecordWriter.java as a `SkipListener` target writing a durable row per skipped item, annotated **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** so the write commits in its own transaction and survives the chunk rollback that Spring Batch performs on skip, per FR-013 and Constitution I — the default propagation would join the chunk transaction and the reject row would be discarded with it, losing the record silently
- [ ] T064 [US3] Implement account-number masking to the last four characters **at write time** before `raw_row_excerpt` is persisted, in src/main/java/com/ledgersync/batch/persistence/RejectedRecordWriter.java per FR-031 and data-model.md §3 — the plaintext must never reach storage
- [ ] T065 [US3] Wire ThresholdSkipPolicy and the skip listener into the `stageTransactions` step in src/main/java/com/ledgersync/batch/config/SettlementJobConfig.java, and set `early_stopped` plus emit `EARLY_STOP_THRESHOLD` with `readAbandoned: true` and `recordsRead` per FR-015a
- [ ] T066 [US3] Ensure any rejected record forces the run to FAILED in src/main/java/com/ledgersync/batch/reconciliation/ReconciliationService.java, since observed totals are compared against the **full** header unreduced by rejects, per FR-020 and clarification Q1

**Checkpoint**: A defective file yields its complete defect list in one pass and still refuses to settle.

---

## Phase 6: User Story 4 - Reprocess a corrected file after a failed run (Priority: P4)

**Goal**: A corrected file for a failed date settles normally; a byte-identical redelivery is
ignored; a new file for an already-settled date waits for an operator.

**Independent Test**: Run the four-step sequence in [quickstart.md](./quickstart.md) Scenario 5 and
verify both the failed and successful runs remain queryable with distinct execution ids.

### Tests for User Story 4

- [ ] T067 [P] [US4] Integration test in src/test/java/com/ledgersync/batch/integration/RedeliveryIT.java covering the Scenario 5 sequence: fail → identical redelivery ignored → corrected file settles → different file for settled date held, per FR-006, FR-006a, FR-007 and FR-027
- [ ] T068 [P] [US4] Test in src/test/java/com/ledgersync/batch/integration/FingerprintConcurrencyIT.java asserting the unique index rejects a second run for the same fingerprint even under near-simultaneous delivery per SC-008

### Implementation for User Story 4

- [ ] T069 [US4] Implement duplicate detection using the `content_fingerprint` unique index in src/main/java/com/ledgersync/batch/ingestion/LandingZoneScanner.java — record the redelivery as observed but create no new settlement run, per FR-006, FR-027 and research R8
- [ ] T070 [US4] Implement the business-date eligibility check in src/main/java/com/ledgersync/batch/ingestion/LandingZoneScanner.java: new fingerprint + date already SETTLED → hold for operator; new fingerprint + date FAILED → process as correction, per FR-006a and FR-007
- [ ] T071 [US4] Implement the `SETTLED_DATE_CONFLICT` alert in src/main/java/com/ledgersync/batch/alerting/ per contracts/alert-hook.md §1
- [ ] T072 [US4] Implement business-date-ordered selection of queued files in src/main/java/com/ledgersync/batch/ingestion/LandingZoneScanner.java so an earlier date is never settled after a later one, per FR-025c
- [ ] T073 [US4] Ensure failed runs are retained and queryable by execution id after a corrective run settles the same date, in src/main/java/com/ledgersync/batch/persistence/ per SC-009

**Checkpoint**: All four user stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Requirements that span stories, plus the validation that proves the whole feature

- [ ] T074 Implement restart handling in src/main/java/com/ledgersync/batch/config/SettlementJobConfig.java deleting staged rows for the execution before re-staging, so a resumed run cannot double-count, per FR-028 and research R6
- [ ] T075 Integration test in src/test/java/com/ledgersync/batch/integration/AtomicityIT.java using transaction-level fault injection during the apply step, asserting balances are either fully updated or wholly untouched, per FR-025 and SC-005 — automate this rather than relying on manual process kills
- [ ] T076 [P] Integration test in src/test/java/com/ledgersync/batch/integration/ReproducibilityIT.java replaying the same file against the same opening balances at two different chunk sizes and asserting identical closing balances, per FR-025b and SC-011 — this is the test that would catch an order-sensitive rule being introduced into the apply path
- [ ] T077 [P] Performance test in src/test/java/com/ledgersync/batch/integration/ThroughputIT.java settling a 500,000-transaction file within 30 minutes of run start, per SC-001
- [ ] T078 [P] Test in src/test/java/com/ledgersync/batch/integration/LoggingComplianceIT.java asserting every log line is valid JSON, carries a consistent `executionId`, and contains no unmasked account number, per Constitution V and FR-031
- [ ] T079 [P] Test in src/test/java/com/ledgersync/batch/unit/MdcLifecycleTest.java asserting MDC is cleared after job and chunk completion so context cannot bleed between executions, per Constitution V
- [ ] T080 Implement retention for `settlement_run`, `settlement_staged_transaction` and `settlement_rejected_record` in src/main/resources/db/changelog/ and a scheduled purge in src/main/java/com/ledgersync/batch/persistence/, following the organisation's financial record retention standard, per FR-016 and FR-029 — staged rows for terminal runs are the bulk and should purge soonest
- [ ] T081 Verify the unit test phase requires no Docker and no Spring context by running `mvn test` with the Docker daemon stopped, and assert this in CI configuration per Constitution Development Workflow
- [ ] T082 [P] Add CI pipeline configuration running ArchUnit (T024), unit tests, and integration tests as merge-blocking gates per Constitution Development Workflow — gates MUST NOT be bypassed to land a change
- [ ] T083 [P] Document the operational runbook in docs/runbook.md: landing zone layout, archive locations, alert meanings, how to reprocess a failed date, and **the exact query an operator runs to retrieve a rejection reason by execution id or business date** per SC-007
- [ ] T084 Run every scenario in [quickstart.md](./quickstart.md) against a deployed instance and tick its Readiness checklist

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup — **blocks all user stories**
- **User Stories (Phases 3–6)**: All depend on Foundational
- **Polish (Phase 7)**: Depends on all desired stories being complete

### User Story Dependencies

Unlike a typical CRUD feature, these stories share one job pipeline, so they are *independently
testable* but not fully *independently deployable*:

- **US1 (P1)**: Depends only on Foundational. Delivers the MVP.
- **US2 (P2)**: Depends on Foundational. Exercises the failure branch of the reconcile step US1
  builds, so it is cheapest immediately after US1 — but its tests stand alone.
- **US3 (P3)**: Depends on Foundational. Adds validation and skip machinery to the stage step. T066
  modifies `ReconciliationService`, created in US1's T034.
- **US4 (P4)**: Depends on Foundational. Touches only eligibility and run lookup; the least
  entangled of the four.

### Within Each User Story

- Tests MUST be written and MUST FAIL before implementation
- Records and entities before services
- Services before job wiring
- Job wiring before integration assertions

### Parallel Opportunities

- T003–T009 (Setup) can run in parallel
- T011, T012, T013 (changelogs) can run in parallel; T010 wires them
- T014, T015, T018, T019, T023, T024 can run in parallel within Foundational
- All `[P]` test tasks within a story can run in parallel
- With a team: after Foundational, one developer per story — US4 is the most separable

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests together first (they must fail):
Task: "Create clean-file fixtures in src/test/resources/fixtures/"
Task: "Unit test FileHeaderParser in src/test/java/com/ledgersync/batch/unit/FileHeaderParserTest.java"
Task: "Unit test ReconciliationService in src/test/java/com/ledgersync/batch/unit/ReconciliationServiceTest.java"
Task: "Integration test in src/test/java/com/ledgersync/batch/integration/CleanFileSettlementIT.java"

# Then the independent records together:
Task: "Create TransactionRecord.java and FileHeader.java in src/main/java/com/ledgersync/batch/ingestion/dto/"
Task: "Create ReconciliationResult.java in src/main/java/com/ledgersync/batch/reconciliation/dto/"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational — large here, because a batch pipeline has a real shared spine
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: quickstart Scenario 1
5. Demo: a clean file settles unattended

### Incremental Delivery

1. Setup + Foundational → spine ready
2. US1 → clean files settle (**MVP**)
3. US2 → unbalanced files refused, every failure mode alerts — *do not run in production before this*
4. US3 → complete defect reporting
5. US4 → redelivery and correction handling
6. Polish → retention, atomicity, reproducibility and throughput proof

> **Production gate**: US1 alone is demonstrable but **must not be run against real balances**.
> Until US2 lands, an unbalanced file settles silently — the exact corruption Principle I exists to
> prevent. Treat US1+US2 as the smallest safe production increment.

### Parallel Team Strategy

1. Team completes Setup + Foundational together
2. Then: Developer A → US1, Developer B → US2 (coordinating on `ReconciliationService`),
   Developer C → US3, Developer D → US4
3. US4 is the most separable; US1 and US2 are the most coupled

---

## Analysis Remediation (2026-10-16)

How each `/speckit-analyze` finding was resolved.

| Finding | Severity | Resolution |
|---|---|---|
| C1 SkipListener transaction | CRITICAL | T063 now mandates `REQUIRES_NEW`; T057 is a new test asserting reject rows survive chunk rollback |
| A1 Money precision | HIGH | spec FR-010 pins 2 decimal places and a two-digit minor unit; T053 and T060 enforce it; T052 adds a 3-dp fixture |
| U1 Reject retention / query | MEDIUM | T058 verifies query-by-business-date; T080 is a new retention task |
| G1 Ordering unverified | MEDIUM | T076 is a new reproducibility test replaying at two chunk sizes |
| G2 Alert latency unmeasured | MEDIUM | T042 is a new latency test against SC-006 |
| I1 Deadline vs cutoff | MEDIUM | spec FR-002c ties the deadline to the cutoff; T006 validates it at startup |
| C2 Changeset mutability | MEDIUM | former T013 folded into T011 — one changeset, immutable once merged |
| I2 Missed-delivery shipped last | MEDIUM | DeliveryDeadlineMonitor moved from Polish to US2 as T050 |
| D1 FR-020/FR-021 overlap | MEDIUM | spec FR-021 narrowed to alerting only |
| D2 FR-006/FR-027 overlap | LOW | spec FR-027 now cross-references FR-006 rather than restating it |
| U2 Type set vs contract | LOW | spec FR-011 states the set is contract-bound; T060 echoes it |
| C3 ArchUnit gaps | LOW | T024 adds a record-as-`@Entity` rule |
| C4 PR template | LOW | T009 creates `.github/pull_request_template.md` |
| G3 Diagnosis path | LOW | T083 requires the runbook to carry the exact operator query |
| I3 Phantom file in plan | LOW | plan.md tree now lists `SettlementChunkListener.java`, `DeliveryDeadlineMonitor.java`, `ContentFingerprinter.java` to match the tasks |
| I4 ArchUnit vacuous early | LOW | T024 labelled a standing gate |

---

## Notes

- `[P]` tasks touch different files and have no incomplete dependencies
- Every task names its file path and cites the requirement or research decision it implements
- Commit after each task or logical group
- Stop at any checkpoint to validate a story independently
- Constitution gates (T024, T081, T082) are merge-blocking and must not be bypassed

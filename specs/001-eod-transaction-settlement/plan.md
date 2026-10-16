# Implementation Plan: End-of-Day Transaction Settlement

**Branch**: `001-eod-transaction-settlement` (feature directory; the working git branch is `master`)
| **Date**: 2026-10-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-eod-transaction-settlement/spec.md`

## Summary

Settle a partner's daily transaction file without human involvement: notice the sentinel, decrypt
and read the file, validate every row, prove the file balances against its own header, and only
then move customer balances — or fail the run and raise an alert, having touched nothing.

The design turns on one consequence of clarification Q1. Because a file must be proven to balance
*before* any balance moves, reading and applying cannot be the same pass. The job therefore splits
into three steps: a chunked **stage** step that reads the file into a staging table, a **reconcile**
tasklet that compares staged aggregates against the header, and an **apply** step that writes
balances only if reconciliation passed. The apply step aggregates staged rows per account and
writes one set-based update inside a single transaction, which is what makes FR-025's
all-or-nothing promise affordable — it writes one row per *account*, not one per transaction.

## Technical Context

**Language/Version**: Java 21 (constitution: Technology Stack)

**Primary Dependencies**: Spring Boot 3.x, Spring Batch 5, Liquibase, Jackson, Logback with a JSON
encoder, Bouncy Castle (PGP decryption — see research R4)

**Storage**: PostgreSQL 16 (confirmed 2026-10-16 — see [research R1](./research.md))

**Testing**: JUnit 5, `spring-batch-test`, AssertJ, Testcontainers for database-backed integration
tests

**Target Platform**: Linux server, JVM 21, scheduled/triggered batch process (no HTTP surface)

**Project Type**: Single-module Maven batch service

**Performance Goals**: 500,000 transactions settled within 30 minutes of run start (SC-001);
settlement complete before the 06:00 local cutoff on ≥99% of business days (SC-002)

**Constraints**: Runs strictly serial (FR-025a); all balance updates for a run atomic (FR-025);
chunk size default 1000 (constitution IV); no raw database keys in logs, alerts, or error records
(constitution I, FR-030)

**Scale/Scope**: ~500,000 transactions per daily file; one producer; one business date per file;
38 functional requirements, 11 success criteria

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Initial evaluation (pre-research)

| # | Principle | Gate | Status |
|---|-----------|------|--------|
| I | Security & Transactional Integrity | Every DB interaction inside an explicit `@Transactional` with declared propagation; reads marked `readOnly = true`; no raw keys in logs/alerts/error store | PASS by design — see R7, R8 |
| II | Spring Batch Layered Architecture | Work expressed as `Job`/`Step`/`ItemReader`/`ItemProcessor`/`ItemWriter`; ingestion, reconciliation and persistence strictly separated | PASS with one documented deviation (apply step — see Complexity Tracking) |
| III | Explicit Dependencies & Immutable Carriers | Constructor injection only; `private final` collaborators; DTOs as `record`; records never used as entities | PASS by design |
| IV | Versioned Schema & Resilient Chunking | Liquibase changelogs only, `ddl-auto: validate`; chunk size 1000; bounded skip/retry; durable reject records | PASS — threshold is bounded by FR-015 (25% after a 1,000-record floor); custom `SkipPolicy` required, see R5 |
| V | Structured Observability & Traceability | JSON logging; unique Execution ID in MDC propagated through every layer and cleared on completion | PASS by design — see R9 |

**Result: PASS.** One deviation recorded under Complexity Tracking; no unjustified violations.

### Post-design re-evaluation (after Phase 1)

| # | Principle | Finding |
|---|-----------|---------|
| I | Security | Each step declares its own transaction boundary. The apply step runs as one transaction spanning all account updates. The staging and error tables key on an opaque `execution_id` (UUID) and a business `account_number`; no surrogate key is exposed in any alert, log line, or error record. **PASS** |
| II | Architecture | Three packages with inward-only dependencies (`ingestion` → `reconciliation` → `persistence` is never reversed). Reconciliation logic is plain Java over records, testable without a Spring context or a database. The apply step uses a Tasklet rather than a chunk loop — justified below. **PASS with deviation** |
| III | Code Quality | All DTOs are records (`TransactionRecord`, `FileHeader`, `ReconciliationResult`, `RejectedRecord`, `AlertPayload`). JPA/JDBC entities are separate mutable types confined to `persistence`. **PASS** |
| IV | Database & Performance | Four changelogs, no manual DDL. Stage step chunked at 1000. `ThresholdSkipPolicy` implements the 25%-after-1000 rule, making the skip limit explicit and bounded. Every skip writes a durable `settlement_rejected_record` row. **PASS** |
| V | Observability | `execution_id` is generated in a `JobExecutionListener`, put into MDC there, re-bound per chunk, and cleared in `afterJob` and in a chunk listener. JSON encoder configured in `logback-spring.xml`. **PASS** |

**Result: PASS.** The single deviation is unchanged and remains justified.

## Project Structure

### Documentation (this feature)

```text
specs/001-eod-transaction-settlement/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── transaction-file.md   # Partner file + sentinel contract
│   └── alert-hook.md         # Outbound alert payload contract
├── checklists/
│   └── requirements.md  # Spec quality checklist (/speckit-specify output)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
pom.xml

src/main/java/com/ledgersync/batch/
├── LedgerSyncBatchApplication.java
├── config/
│   ├── SettlementJobConfig.java        # Job + three Step definitions
│   ├── BatchInfrastructureConfig.java  # JobRepository, serial JobLauncher
│   └── SettlementProperties.java       # chunk size, thresholds, paths (record-backed)
├── ingestion/                          # Ingestion Layer
│   ├── LandingZoneScanner.java         # sentinel detection, delivery eligibility
│   ├── DeliveryDeadlineMonitor.java    # missed-delivery alerting (FR-002b)
│   ├── ContentFingerprinter.java       # streaming SHA-256 of encrypted bytes
│   ├── FileDecryptor.java
│   ├── TransactionFileReader.java      # ItemReader over decrypted rows
│   ├── FileHeaderParser.java
│   ├── TransactionRecordMapper.java
│   └── dto/                            # TransactionRecord, FileHeader (records)
├── reconciliation/                     # Business / Reconciliation Logic
│   ├── RecordValidator.java            # FR-008..FR-012, no I/O
│   ├── ValidationProcessor.java        # ItemProcessor wrapper
│   ├── ReconciliationService.java      # FR-017..FR-023, pure arithmetic
│   ├── ThresholdSkipPolicy.java        # FR-015 25%-after-1000
│   └── dto/                            # ReconciliationResult, RejectedRecord (records)
├── persistence/                        # Data Persistence Layer
│   ├── entity/                         # StagedTransaction, SettlementRun, ... (mutable)
│   ├── repository/
│   ├── StagedTransactionWriter.java    # ItemWriter
│   ├── RejectedRecordWriter.java       # SkipListener target
│   └── BalanceApplier.java             # set-based aggregate update
├── alerting/
│   └── SettlementAlertPublisher.java
└── observability/
    ├── ExecutionIdJobListener.java     # MDC bind/clear
    └── SettlementChunkListener.java    # per-chunk MDC re-bind

src/main/resources/
├── application.yml
├── logback-spring.xml                  # JSON encoder
└── db/changelog/
    ├── db.changelog-master.yaml
    ├── 001-settlement-run.yaml
    ├── 002-staged-transaction.yaml
    ├── 003-rejected-record.yaml
    └── 004-customer-balance.yaml

src/test/java/com/ledgersync/batch/
├── unit/           # RecordValidator, ReconciliationService, ThresholdSkipPolicy — no Spring, no DB
├── integration/    # Full job runs against Testcontainers Postgres
└── fixtures/       # Sample files: clean, rejects, imbalanced, wrong-format
```

**Structure Decision**: Single-module Maven project, packaged by the three architectural layers
Constitution Principle II names — `ingestion`, `reconciliation`, `persistence` — rather than by
Spring stereotype. Package-by-layer here is not merely cosmetic: it makes a cross-layer import
visible in review, which is exactly the leakage Principle II asks reviewers to reject. The
`reconciliation` package depends on neither Spring Batch I/O types nor the persistence entities, so
its tests run with no container and no database, satisfying the Development Workflow gate.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Apply step is a Tasklet with a set-based aggregate update, not a chunk-oriented `ItemReader`/`ItemWriter` loop (deviates from Principle II's chunk mechanics and Principle IV's chunking default) | FR-025 requires every balance update in a run to take effect as a whole or not at all. A chunked writer commits per chunk, so an interruption at chunk 300 of 500 leaves balances half-updated — the exact outcome FR-025 forbids and SC-005 measures. Aggregating staged rows per account first collapses ~500,000 transactions into the number of *distinct accounts touched*, making a single-transaction write affordable. | **Chunked apply with compensating reversal**: requires a reversal path that must itself be atomic, so the problem recurses, and a failed compensation leaves the ledger wrong — unacceptable under Principle I. **Chunked apply inside one outer transaction**: holds a transaction open across the whole write, giving the atomicity but none of the chunking benefit, while adding lock duration and rollback-segment pressure. **Keep chunking and relax FR-025**: not available — partial settlement is a financial-correctness requirement, not a preference. |

Note that the *stage* step — which does the heavy reading of 500,000 rows — remains fully
chunk-oriented at the constitutional default of 1000, with skip/retry applied there. The deviation
is confined to the write of aggregated balances.

# LedgerSync-Batch Constitution

## Core Principles

### I. Financial Data Security & Transactional Integrity

Financial data compliance is absolute and overrides convenience, velocity, and elegance.

- Every database interaction MUST execute inside an explicit transaction boundary declared
  with `@Transactional`. Relying on implicit, ambient, or inherited transaction context is
  prohibited.
- `@Transactional` MUST declare its propagation and read-only intent wherever the default is
  not correct for the operation. Read paths MUST be marked `readOnly = true`.
- Raw database keys (surrogate primary keys, sequence values, internal row identifiers) MUST
  NEVER be exposed outside the persistence layer — not in API responses, exported files, log
  output, error messages, or job parameters. External references MUST use an opaque business
  identifier or UUID.
- Personally identifying and account-level financial fields MUST be masked or omitted in all
  log output.

Rationale: Partial writes and leaked internal keys are the two failure modes that turn a batch
defect into a reportable compliance incident. Both are prevented at the code level, not by
review vigilance.

### II. Spring Batch Layered Architecture

The service is built on Spring Batch 5 core mechanics and MUST NOT reimplement them.

- Batch work MUST be expressed through the framework contracts: `Job`, `Step`, `ItemReader`,
  `ItemProcessor`, `ItemWriter`. Hand-rolled loops, bespoke schedulers, or ad-hoc orchestration
  in place of a `Step` are prohibited.
- Three layers MUST remain strictly separated, with dependencies pointing inward only:
  - **Ingestion Layer** — readers, source adapters, parsing, input validation. MUST NOT contain
    reconciliation rules and MUST NOT write to the domain datastore.
  - **Business/Reconciliation Logic** — processors and domain services. MUST be free of
    framework I/O concerns and MUST be unit-testable without a database or Spring context.
  - **Data Persistence** — writers, repositories, entity mapping. MUST NOT contain business
    rules.
- Cross-layer leakage (an entity used as an ingestion DTO, a reader invoking reconciliation,
  a processor issuing writes) MUST be rejected in review.

Rationale: Spring Batch already solves restartability, chunking, and execution state.
Reimplementation forfeits those guarantees. Layer boundaries keep reconciliation logic testable
in isolation, which is where correctness risk concentrates.

### III. Explicit Dependencies & Immutable Data Carriers

- Dependency injection MUST use constructor injection. Field injection via `@Autowired` on
  fields is prohibited. Setter injection is permitted only where a framework contract demands
  it, and MUST be justified in a code comment.
- Collaborator fields MUST be declared `private final`.
- Data Transfer Objects MUST be declared as Java `record` types and MUST be immutable. Mutable
  DTO classes exposing getters and setters are prohibited.
- Records MUST NOT be used as JPA entities; entity and DTO types remain distinct.

Rationale: Constructor injection makes dependencies visible and makes classes constructible in
tests without a container. Immutable records eliminate a class of state-mutation defects that
are especially hard to trace inside a long-running chunked batch.

### IV. Versioned Schema & Resilient Chunk Processing

- All database schema changes MUST be applied through Liquibase changelogs held in version
  control. Manual DDL against any environment is prohibited, as is any Hibernate `ddl-auto`
  value other than `validate` or `none`.
- Changesets MUST be treated as immutable once merged; corrections require a new changeset.
- Processing MUST be chunk-based. The default chunk size is **1000 records**; any deviation
  MUST be externally configurable and justified by measured evidence.
- A corrupt, malformed, or individually failing row MUST NOT abort the batch run. Steps MUST
  configure skip and retry policies with explicit, bounded limits.
- Every skipped item MUST be recorded with its identifier and failure reason in a durable,
  auditable form. Silent skipping is prohibited.

Rationale: A nightly reconciliation run that dies on row 40,000 of 2,000,000 because of one bad
record costs an operational window. Bounded skip and retry with a durable reject record keeps
the run alive while preserving the audit trail compliance requires.

### V. Structured Observability & Execution Traceability

- All log output MUST be structured JSON. Unstructured or string-concatenated log lines are
  prohibited.
- Every batch run MUST generate a unique Execution ID at job start.
- The Execution ID MUST be placed in the MDC and propagated through every layer, including
  worker and chunk-scoped threads, so that all log events emitted by a run are correlatable by
  that single identifier.
- MDC context MUST be cleared on thread completion to prevent context bleed between executions.
- Each run MUST log, at minimum: job start, job end, terminal status, records read, records
  written, and records skipped.

Rationale: Batch failures are diagnosed after the fact, from logs alone. Without a correlating
Execution ID carried through MDC, interleaved concurrent runs are unreadable and post-incident
reconstruction is impossible.

## Technology Stack & Compliance Constraints

- **Language/Runtime**: Java 21. Older language constructs MUST NOT be used as a workaround for
  the principles above.
- **Build**: Maven. Dependency versions MUST be centrally managed; version declarations
  scattered across modules are prohibited.
- **Framework**: Spring Boot 3.x with Spring Batch 5.
- **Schema Management**: Liquibase, per Principle IV.
- Adding a dependency that duplicates capability already provided by Spring Boot or Spring
  Batch MUST be justified in writing before merge.
- Credentials, connection strings, and secrets MUST be externalized from source control.

## Development Workflow & Quality Gates

- Reconciliation and business logic MUST carry unit tests that run without a database or a
  Spring context.
- Each `Job` MUST have at least one integration test covering a successful run, and one
  covering the skip/retry path with deliberately corrupt input.
- A pull request MUST NOT merge while any of the following is present: field `@Autowired`,
  a mutable DTO class, raw key exposure, a database interaction outside an explicit
  `@Transactional` boundary, manual DDL, or unstructured logging.
- Failure of any automated gate blocks merge. Gates MUST NOT be bypassed or disabled in order
  to land a change.
- Every pull request description MUST state which principles the change touches and how
  compliance was verified.

## Governance

This constitution supersedes all other development practices, conventions, and individual
preferences for the LedgerSync-Batch service. Where any other document conflicts with it, this
constitution prevails.

**Amendment procedure**: Amendments MUST be proposed as a pull request modifying this file,
MUST state the rationale and the migration impact on existing code, and MUST be approved by the
technical owner of the service before merge. An amendment that invalidates existing code MUST
include a remediation plan naming an owner and a target date.

**Versioning policy**: This constitution is versioned using semantic versioning.

- **MAJOR** — a principle is removed, or redefined such that previously compliant code becomes
  non-compliant.
- **MINOR** — a new principle or section is added, or existing guidance is materially expanded.
- **PATCH** — clarification, wording, or typo correction with no change in meaning.

**Compliance review**: Compliance is verified at pull request review and enforced by automated
build gates wherever a rule is mechanically checkable. Reviewers MUST reject non-compliant
changes regardless of delivery pressure. Any justified exception MUST be documented in the pull
request and MUST carry an expiry date; an undocumented exception is a violation.

**Version**: 1.0.0 | **Ratified**: 2026-10-16 | **Last Amended**: 2026-10-16

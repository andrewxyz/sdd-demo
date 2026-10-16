# Summary

<!-- What changes and why. Link the spec requirement or task id. -->

## Constitution compliance

Constitution Development Workflow requires every PR to state which principles the change touches
and how compliance was verified. Tick what applies and say how you checked.

- [ ] **I — Security & Transactional Integrity**: every DB interaction inside an explicit
      `@Transactional` with declared propagation; reads marked `readOnly = true`; no raw database
      keys in logs, alerts or error records
- [ ] **II — Spring Batch Layered Architecture**: work expressed through `Job`/`Step`/`ItemReader`/
      `ItemProcessor`/`ItemWriter`; no cross-layer leakage between ingestion, reconciliation and
      persistence
- [ ] **III — Explicit Dependencies & Immutable Carriers**: constructor injection only;
      `private final` collaborators; DTOs are records; no record used as a JPA entity
- [ ] **IV — Versioned Schema & Resilient Chunking**: schema changed only via a new Liquibase
      changeset; chunking preserved; skip limits explicit and bounded; every skip durably recorded
- [ ] **V — Structured Observability**: JSON logging; `executionId` in MDC and cleared on
      completion

**How verified**:

<!-- e.g. "ArchUnit ConstitutionRulesTest passes; added RejectSurvivesRollbackIT" -->

## Merge blockers

A PR MUST NOT merge while any of these is present. Confirm none are:

- [ ] No field-level `@Autowired`
- [ ] No mutable DTO classes
- [ ] No raw key exposure
- [ ] No database interaction outside an explicit `@Transactional` boundary
- [ ] No manual DDL
- [ ] No unstructured logging

## Tests

- [ ] Unit tests added or updated, and they run without a database or Spring context
- [ ] Integration tests added or updated where a `Job` behaviour changed

## Exceptions

<!-- Any justified exception MUST be documented here WITH an expiry date.
     An undocumented exception is a violation. -->

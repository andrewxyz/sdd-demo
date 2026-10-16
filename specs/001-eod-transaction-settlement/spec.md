# Feature Specification: End-of-Day Transaction Settlement

**Feature Branch**: `master` (no branch hook installed; spec directory is `specs/001-eod-transaction-settlement`)

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "Write a functional specification for the End-of-Day Transaction Settlement feature. Goal: Systematically process incoming customer transactions from external file drops to settle daily balances. Inflow: A batch job triggers automatically when a new encrypted transaction CSV arrives in the target landing zone. Validation: The system must validate each record's format (Account Number, Transaction Amount, Type, Timestamp). Invalid rows must be safely detoured into an error table with reasons logged, without crashing the batch. Balancing: The service must sum all transaction deltas and match them against the total header row count/amount. If the ledger doesn't reconcile (Net Total != Header Summary), the job must status as FAILED and trigger an urgent alert hook. Outcome: Successfully reconciled transactions update customer balances and mark the batch run as SETTLED."

## Clarifications

### Session 2026-10-16

- Q: When a file contains rejected rows, should observed totals be compared against the full header
  summary or the header summary reduced by rejected rows? → A: Against the full header summary. Any
  rejection makes the file unreconcilable and the run FAILED — but the run still reads every row
  before failing, so the error store holds a complete list of defects from a single pass.
- Q: May two settlement runs execute at the same time? → A: No. Runs are strictly serial — one at
  a time, others queue. Throughput comes from chunked processing within a run, and transactions
  affecting the same account retain their file order.
- Q: What makes two deliveries "the same file" for the purpose of never applying transactions
  twice? → A: Two separate rules. A matching content fingerprint means a true redelivery and is
  ignored; a different file for an already-SETTLED business date is held for an operator; a
  different file for a FAILED date proceeds as a correction.
- Q: How should the service know a partner has finished writing a file rather than catching it
  mid-upload? → A: By a completion marker (sentinel) file the producer writes after the data file.
  The sentinel is the trigger; a data file without one is never read.
- Q: At what point should the service stop reading a file early rather than finishing the pass?
  → A: Above 25% rejected records, evaluated only after at least 1,000 records have been read. This
  catches wrong-file and wrong-format deliveries while letting ordinary defect levels produce a
  complete rejection report.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Settle a clean daily transaction file (Priority: P1)

A partner institution drops the day's encrypted transaction file into the landing zone after their
close of business. Without anyone touching a console, the settlement service notices the file,
opens it, checks every record, confirms the file's own declared totals agree with what it actually
read, applies the resulting balance changes to customer accounts, and marks the run SETTLED.
By the time Settlement Operations arrives the next morning, the day is closed and balances are
current.

**Why this priority**: This is the feature. Every other story protects or recovers this path. If
only this story shipped, the business would have automated end-of-day settlement for well-formed
files, which is the whole reason the service exists.

**Independent Test**: Drop a well-formed file whose header totals match its rows into the landing
zone, then verify without further input that every account balance moved by exactly its transaction
delta and the run is recorded as SETTLED. Delivers automated daily settlement on its own.

**Acceptance Scenarios**:

1. **Given** an empty landing zone and a set of customer accounts with known opening balances,
   **When** a valid file containing 500 transactions arrives whose header declares 500 records and
   a net total matching the sum of those transactions, **Then** each affected account balance
   equals its opening balance plus its net delta, and the run status is SETTLED.
2. **Given** a valid file has been fully processed and marked SETTLED, **When** Settlement
   Operations reviews the run, **Then** the run shows a unique execution identifier, the source
   file name, the business date, records read, records applied, and the reconciled net total.
3. **Given** a valid file arrives outside business hours with no operator present, **When** the
   file lands, **Then** processing begins automatically with no manual trigger.

---

### User Story 2 - Block settlement when the ledger does not balance (Priority: P2)

A file arrives whose contents do not agree with its own declared header summary — rows were lost in
transit, truncated, or duplicated upstream. Rather than quietly writing wrong numbers to customer
balances, the service refuses to settle, marks the run FAILED, and raises an urgent alert naming
exactly what it expected versus what it found, so the on-call engineer can chase the upstream
provider before the banking day opens.

**Why this priority**: This is the financial safety control. Settling an unbalanced file corrupts
customer balances, and corrupted balances in a financial ledger are a reportable incident. Shipping
story 1 without this means an upstream truncation becomes silent data corruption.

**Independent Test**: Submit a file whose header declares a net total deliberately different from
the sum of its rows, then verify that no customer balance changed, the run is FAILED, and an alert
was raised carrying the expected and actual figures.

**Acceptance Scenarios**:

1. **Given** a file whose rows sum to 10,000.00 but whose header declares a net total of 12,500.00,
   **When** the run reaches reconciliation, **Then** no customer balance is modified, the run status
   is FAILED, and an urgent alert is raised.
2. **Given** a file whose header declares 1,000 records but which contains 998 rows, **When** the
   run reaches reconciliation, **Then** the run status is FAILED and the alert states both the
   declared and the observed record count.
3. **Given** a reconciliation failure has occurred, **When** the on-call engineer opens the alert,
   **Then** it identifies the execution identifier, source file name, business date, and the
   expected versus actual count and net total, without exposing any internal record identifier.

---

### User Story 3 - Report every bad row from a single pass (Priority: P3)

A file is mostly good but carries a handful of damaged rows — a blank account number here, a
timestamp in the wrong format there. Rather than stopping dead on row 40,000, the service sets each
bad row aside in an error store with a plain-language reason and keeps reading to the end of the
file. The file will not settle, because a file that lost rows cannot be proven to balance, but by
morning Settlement Operations holds the *complete* list of what to fix — not just the first defect
— so one correction round trip replaces many.

**Why this priority**: Operator efficiency and diagnosis speed. Without it, each bad row costs a
full processing window: fix one, resubmit, discover the next. It ranks below story 2 because a
delayed correction cycle is recoverable whereas a corrupted balance is not.

**Independent Test**: Submit a file containing several deliberately malformed rows of differing
kinds, then verify the run read through to the last row rather than stopping at the first defect,
that every malformed row appears in the error store with an accurate reason, and that no balance
moved.

**Acceptance Scenarios**:

1. **Given** a file of 1,000 rows of which 7 have defects spread across the file, **When** the run
   processes the file, **Then** reading does not stop at the first defect and all 7 rows are
   recorded in the error store.
2. **Given** a row is rejected for a missing account number, **When** an operator inspects the error
   store, **Then** the entry names the failing field, states the reason in plain language, and
   carries the execution identifier and rejection time.
3. **Given** a file produced any rejected rows, **When** the run reaches reconciliation, **Then**
   the run status is FAILED, no customer balance has changed, and the complete rejection list
   remains available for correction.

---

### User Story 4 - Reprocess a corrected file after a failed run (Priority: P4)

After a reconciliation failure, the upstream provider sends a corrected file for the same business
date. Settlement Operations needs that corrected file to settle normally, while the original failed
attempt stays on record for audit.

**Why this priority**: Recovery path. It only matters once stories 2 and 3 can produce a failure,
and in the interim a failed date can be handled by manual escalation.

**Independent Test**: Cause a run to fail on reconciliation, then submit a corrected file for the
same business date and verify it settles while the failed attempt remains queryable.

**Acceptance Scenarios**:

1. **Given** a FAILED run exists for a business date, **When** a corrected file for that date
   arrives, **Then** it is processed and, if it reconciles, the date is marked SETTLED.
2. **Given** a business date is already SETTLED, **When** another file for that same date arrives,
   **Then** it is not settled automatically and the condition is raised for operator attention.
3. **Given** a corrected file has settled a previously failed date, **When** an auditor reviews that
   date, **Then** both the failed and the successful run remain visible with their own execution
   identifiers.

---

### Edge Cases

- **Empty file**: a file arrives containing a header declaring zero records and no transaction rows.
  Treated as a valid, reconciled run of zero value rather than an error.
- **Header missing or unreadable**: reconciliation has nothing to compare against, so the run cannot
  be proven correct and must fail rather than settle on trust.
- **Decryption fails**: the file cannot be opened at all. No records are read and no balances move.
- **File still being written**: the producer is mid-upload when the landing zone is observed. The
  absence of a sentinel holds the file back until delivery completes.
- **Orphan data file**: a data file lands but its sentinel never follows. The file is never read,
  and the missed-delivery deadline is what surfaces it.
- **Orphan sentinel**: a sentinel lands naming a data file that is absent or unreadable.
- **Duplicate delivery**: a byte-identical file arrives twice. The second is recognised by its
  fingerprint and ignored without a new run.
- **Different file, settled date**: a file with a new fingerprint arrives for a date already
  SETTLED — held for an operator rather than settled or silently dropped.
- **Every row invalid**: the file is readable but no row passes validation — typically the wrong
  file or the wrong format entirely. Caught by the early-stop rule once 1,000 records have been
  read, so the processing window is not spent on a hopeless file.
- **Small file, high reject ratio**: a 40-row file with 30 rejects never reaches the 1,000-record
  floor, so it completes its pass and reports all 30.
- **Unknown or closed account**: a row names an account the service does not recognise or that is no
  longer active.
- **Zero-amount transaction**: a row carries an amount of 0.00, which moves no balance.
- **Timestamp outside the business date**: a row is dated to a different day than the file claims.
- **Balance would go negative**: a debit exceeds the account's available balance.
- **Concurrent arrivals**: two files for different business dates land within moments of each
  other. The second queues behind the first, and the earlier business date is settled first.
- **Interrupted run**: the service stops partway through a file and restarts.
- **Precision**: amounts carrying more than 2 decimal places — rejected, never silently rounded.

## Requirements *(mandatory)*

### Functional Requirements

#### Ingestion and run control

- **FR-001**: System MUST detect the arrival of a new encrypted transaction file in the designated
  landing zone and begin processing without manual intervention.
- **FR-002**: System MUST treat the producer's completion marker (sentinel) file as the signal that
  delivery has finished, and MUST NOT read a data file that has no corresponding sentinel.
- **FR-002a**: System MUST raise an alert when a sentinel names a data file that is absent or
  unreadable, since this indicates a broken delivery rather than a late one.
- **FR-002b**: System MUST raise an alert when no settled file has been received for a business
  date by its expected delivery deadline, so that a delivery that never arrives — or a data file
  stranded without its sentinel — cannot pass unnoticed as a quiet night.
- **FR-002c**: The expected delivery deadline MUST fall far enough before the operational cutoff
  in SC-002 to leave room for a full run plus one corrective resend. A deadline that leaves less
  than that is a misconfiguration, because an alert raised too late to act on is no alert at all.
- **FR-003**: System MUST decrypt the file before processing. If decryption fails, the run MUST be
  marked FAILED, an urgent alert MUST be raised, and no records may be read or applied.
- **FR-004**: System MUST assign every run a unique execution identifier at start, and MUST record
  against it the source file name, business date, start time, end time, and terminal status.
- **FR-005**: System MUST maintain a run status drawn from a defined set: PENDING, RUNNING, FAILED,
  SETTLED.
- **FR-006**: System MUST record a content fingerprint for every file it processes, and MUST ignore
  without reprocessing any later delivery whose fingerprint matches one already seen. A redelivery
  of an identical file MUST be recorded as observed but MUST NOT create a new settlement run.
- **FR-006a**: System MUST NOT automatically settle a file whose fingerprint is new but whose
  business date is already marked SETTLED; the condition MUST be raised for operator attention
  instead.
- **FR-007**: System MUST process a file whose fingerprint is new and whose business date has a
  prior FAILED run, treating it as a correction and retaining the failed run as an auditable
  record.

#### Record validation

- **FR-008**: System MUST validate every record for the presence and format of Account Number,
  Transaction Amount, Transaction Type, and Timestamp.
- **FR-009**: System MUST reject a record whose Account Number does not correspond to a recognised,
  active customer account.
- **FR-010**: System MUST reject a record whose Transaction Amount is non-numeric, negative, or
  carries more than **2 decimal places**. The settlement currency MUST have a two-digit minor
  unit; currencies with a different minor unit are out of scope for this feature. Storage retains
  additional decimal places purely as aggregation headroom and MUST NOT be used to accept input of
  greater precision.
- **FR-011**: System MUST reject a record whose Transaction Type is outside the configured set of
  settlement types. That set is bound by the partner file contract, so adding or removing a type
  requires the producer's agreement and a contract revision, not a configuration change alone.
- **FR-012**: System MUST reject a record whose Timestamp is unparseable or falls outside the
  business date declared by the file.
- **FR-013**: System MUST write every rejected record to an error store capturing the source row
  reference, the failing field, a plain-language reason, the execution identifier, and the time of
  rejection.
- **FR-014**: A rejected record MUST NOT stop the run from reading the remainder of the file, and
  MUST NOT alter any customer balance. The run MUST complete a full pass so that every defect in the
  file is captured in one cycle.
- **FR-015**: System MUST stop reading the file early, mark the run FAILED, and raise an urgent
  alert when rejected records exceed 25% of records read, evaluated only once at least 1,000
  records have been read. Below that floor the run MUST always complete its pass. Both the
  percentage and the floor MUST be externally configurable.
- **FR-015a**: When an early stop occurs, the alert MUST say that the file was abandoned partway
  and state how many records were read, so the reader does not mistake a truncated rejection list
  for the file's complete set of defects.
- **FR-016**: Rejected records MUST remain queryable by business date and by execution identifier
  for the audit retention period.

#### Reconciliation

- **FR-017**: System MUST read from the file header the declared total record count and the declared
  net total amount.
- **FR-018**: System MUST compute the observed record count and the observed net total as the signed
  sum of all transaction deltas, where Transaction Type determines each delta's direction.
- **FR-019**: System MUST compare the observed count and net total against the header's declared
  values before any customer balance is modified.
- **FR-020**: System MUST compare observed totals against the **full** header-declared summary,
  unreduced by rejected records. A file producing one or more rejected records therefore cannot
  reconcile, and its run MUST be marked FAILED with no balances applied. A file settles only when
  every record it contains was valid and the totals agree.
- **FR-021**: On reconciliation mismatch, System MUST trigger the urgent alert hook. (The FAILED
  status and the guarantee that balances remain unchanged are stated once in FR-020; this
  requirement governs notification only.)
- **FR-022**: The reconciliation alert MUST state the execution identifier, source file name,
  business date, declared count and net total, observed count and net total, and the number of
  rejected records, so the recipient can tell a transit loss apart from a validation failure.
- **FR-023**: System MUST mark the run FAILED when the header is absent or unreadable, since
  correctness cannot be demonstrated without it.

#### Settlement

- **FR-024**: System MUST apply transaction deltas to customer balances only after reconciliation
  has succeeded.
- **FR-025**: All balance updates belonging to a single run MUST take effect as a whole or not at
  all; a run MUST NOT leave balances partially updated.
- **FR-025a**: Settlement runs MUST execute strictly one at a time. A file arriving while a run is
  in progress MUST be queued, not processed concurrently.
- **FR-025b**: Within a run, transactions affecting the same account MUST be applied in the order
  they appear in the file, so that the resulting balance does not depend on how work was divided
  internally.
- **FR-025c**: Queued files MUST be processed in business-date order, so that an earlier date is
  never settled after a later one.
- **FR-026**: On successful application, System MUST mark the run SETTLED and record records read,
  records rejected, records applied, and the reconciled net total.
- **FR-027**: System MUST apply the transactions of any given file at most once. This is the
  guarantee that the fingerprint mechanism in FR-006 exists to provide; implement it there, not
  twice.
- **FR-028**: System MUST be resumable after interruption without double-applying any transaction
  already applied.

#### Audit and disclosure

- **FR-029**: System MUST retain, for every run, an auditable record of its counts and terminal
  status.
- **FR-030**: System MUST NOT expose raw internal record or account identifiers in alerts, logs, or
  error store entries; external references MUST use an opaque business identifier.
- **FR-031**: System MUST mask account-level financial detail in all log output.

### Key Entities

- **Transaction Record**: One customer transaction read from the file. Carries an account reference,
  an amount, a type that determines whether the amount increases or decreases the balance, and a
  timestamp. Either applied to a balance or rejected — never both.
- **File Control Header**: The file's own declaration of what it contains — total record count and
  net total amount. The independent figure reconciliation is checked against.
- **Completion Marker**: The sentinel the producer writes once a data file is fully delivered.
  Identifies the data file it completes. Its presence is what makes a file eligible to be read.
- **Settlement Run**: One execution against one file. Holds the unique execution identifier, source
  file name, content fingerprint, business date, timings, counts, and terminal status. The unit of
  audit, and the record that makes redelivery detectable.
- **Customer Account Balance**: The running balance a settlement run moves. Changes only through a
  reconciled, applied run.
- **Rejected Record**: A transaction record that failed validation, kept with its failure reason and
  owning execution identifier. The operator's record of what did not settle.
- **Settlement Alert**: An urgent notification raised on reconciliation failure, decryption failure,
  or breach of the rejection threshold. Carries enough detail to act on without opening the file.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A daily file of 500,000 transactions is settled within 30 minutes of its run
  starting. Where a file queues behind another, the queue wait is excluded from this measure.
- **SC-002**: Settlement completes before the 06:00 local operational cutoff on at least 99% of
  business days. The delivery deadline in FR-002c is set relative to this cutoff, not
  independently of it.
- **SC-003**: A file containing defects yields its complete defect list from one pass — measured as
  100% of a file's bad rows reported after a single run, below the rejection threshold. (Reaching
  reconciliation and failing there is not an abort; stopping early at the first bad row is.)
- **SC-004**: 100% of rejected records are retrievable with a stated reason; zero rejected records
  reach a customer balance.
- **SC-005**: Zero partially settled runs — no run ever leaves customer balances in a half-updated
  state, measured across all runs including interrupted ones.
- **SC-006**: 100% of reconciliation failures raise an urgent alert within 2 minutes of detection.
- **SC-007**: An operator can determine why any individual record was rejected within 5 minutes,
  without opening the source file.
- **SC-008**: Zero duplicate settlements — no transaction is applied to a balance more than once,
  including across redeliveries and restarts.
- **SC-009**: 100% of runs are reconstructable after the fact from their execution identifier alone.
- **SC-010**: 100% of business dates with no completed delivery are flagged by their expected
  deadline — a missing file is never mistaken for a quiet night.
- **SC-011**: Balance outcomes are reproducible — replaying the same file against the same opening
  balances yields identical closing balances every time, regardless of internal work division.

## Assumptions

- The transaction file is delivered as encrypted CSV, and the service holds decryption credentials
  through the organisation's existing key management arrangements. Key distribution is out of scope.
- The file carries a single header row declaring total record count and net total amount; this is
  the only control total available for reconciliation.
- Balance updates are applied only after reconciliation succeeds, following the stated requirement
  that successfully reconciled transactions update balances. No compensating-reversal design is
  needed, because balances are never touched before the file is proven to balance.
- Transaction Type resolves to a credit or debit direction; the full set of settlement types is
  defined by the business and treated here as a closed, configurable list.
- Customer accounts and their balances already exist and are owned by an existing system of record.
  This feature changes balances but does not create accounts.
- One file corresponds to one business date. Files spanning multiple business dates are out of
  scope.
- A debit that would overdraw an account is still applied; overdraft policy is enforced elsewhere
  and is out of scope here.
- Expected daily volume is in the order of 500,000 transactions, used to size SC-001.
- Alert delivery is handled by an existing notification channel; this feature triggers it and
  supplies content, but does not own routing or escalation policy.
- Audit retention for runs and rejected records follows the organisation's existing financial record
  retention standard.
- Manual correction and resubmission of individual rejected records is out of scope for this
  feature; rejected records are reported, and correction happens upstream.

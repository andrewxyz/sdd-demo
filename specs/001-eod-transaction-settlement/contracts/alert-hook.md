# Contract: Outbound Settlement Alert Hook

**Date**: 2026-10-16 | **Direction**: LedgerSync-Batch → notification channel (owned elsewhere)

The spec places routing and escalation policy out of scope. This contract defines the payload and
the trigger conditions only; the channel binding is a deployment concern.

---

## 1. Trigger conditions

| Condition | Alert type | Requirement |
|---|---|---|
| Decryption failed | `DECRYPTION_FAILED` | FR-003 |
| Sentinel names a missing or unreadable data file | `BROKEN_DELIVERY` | FR-002a |
| No completed delivery by the business date's deadline | `DELIVERY_OVERDUE` | FR-002b |
| Header missing or unparseable | `HEADER_UNREADABLE` | FR-023 |
| Observed totals ≠ declared totals | `RECONCILIATION_MISMATCH` | FR-021, FR-022 |
| Rejects exceeded 25% after 1,000 records read | `EARLY_STOP_THRESHOLD` | FR-015, FR-015a |
| New file for an already-SETTLED business date | `SETTLED_DATE_CONFLICT` | FR-006a |

All are urgent. A run reaching SETTLED emits no alert.

---

## 2. Payload

JSON. Fields marked **required** are present on every alert type.

```json
{
  "alertType": "RECONCILIATION_MISMATCH",
  "severity": "URGENT",
  "executionId": "7f3a9c21-5e44-4b1d-9a02-0c6f8e5b1d77",
  "sourceFileName": "txn-20261016-01.csv.pgp",
  "businessDate": "2026-10-16",
  "detectedAt": "2026-10-17T02:14:09Z",
  "runStatus": "FAILED",
  "summary": "Observed net total -149.00 does not match declared -150.00",
  "reconciliation": {
    "declaredCount": 3,
    "observedCount": 3,
    "declaredNetTotal": "-150.00",
    "observedNetTotal": "-149.00"
  },
  "rejectedRecordCount": 0,
  "readAbandoned": false,
  "recordsRead": 3
}
```

| Field | Type | Presence | Source |
|---|---|---|---|
| `alertType` | enum (table above) | **required** | — |
| `severity` | `URGENT` | **required** | FR-021 |
| `executionId` | UUID | **required** | FR-022, Constitution V |
| `sourceFileName` | string | **required** | FR-022 |
| `businessDate` | `YYYY-MM-DD` | **required** | FR-022 |
| `detectedAt` | ISO-8601 UTC | **required** | — |
| `runStatus` | enum | **required** | FR-005 |
| `summary` | string, human-readable | **required** | — |
| `reconciliation` | object | present on `RECONCILIATION_MISMATCH` | FR-022 |
| `rejectedRecordCount` | integer | present on reconciliation and threshold alerts | FR-022 |
| `readAbandoned` | boolean | **required** | FR-015a |
| `recordsRead` | integer | **required** | FR-015a |

### Why `readAbandoned` and `recordsRead` are on every alert

FR-015a exists to stop a reader mistaking a truncated rejection list for a complete one. Putting
these two fields on every payload rather than only on `EARLY_STOP_THRESHOLD` means a recipient
never has to know which alert types *can* be truncated in order to ask the question. When
`readAbandoned` is `true`, the rejected records on file cover only the first `recordsRead` rows.

---

## 3. Disclosure constraints

Constitution Principle I and FR-030 govern this payload:

- **No surrogate database identifiers.** `executionId` is a UUID generated for correlation, never
  the `JobRepository` sequence value or any table's `id`.
- **No account numbers, amounts, or customer-identifying fields.** The aggregate figures in
  `reconciliation` are file-level totals, which carry no individual account detail.
- `summary` is generated text and MUST NOT be built by interpolating a row's contents.

A recipient needing row-level detail queries the rejected-record store using `executionId`; the
alert deliberately carries no such detail into the notification channel.

---

## 4. Delivery semantics

- Dispatched **after** the run's transaction has committed or rolled back, never inside it (R11).
  An alert sent inside a transaction that then rolls back would be discarded along with it — the
  run fails and nobody is told.
- At-least-once. The receiver should treat `executionId` plus `alertType` as the idempotency key.
- A failed dispatch is logged at `ERROR` and does **not** change the run's terminal status; the
  ledger outcome is already decided and must not depend on a notification channel.
- No ordering guarantee between alerts from different runs — runs are serial (FR-025a), so in
  practice alerts arrive in run order.

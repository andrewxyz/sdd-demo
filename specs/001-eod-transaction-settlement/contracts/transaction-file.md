# Contract: Partner Transaction File and Sentinel

**Date**: 2026-10-16 | **Parties**: upstream partner institution (producer) → LedgerSync-Batch
(consumer)

This is an agreement with an external party. Changing it requires the producer's agreement, not
just a code change on this side.

---

## 1. Delivery protocol

The producer writes two files into the landing zone, **in this order**:

1. The encrypted data file.
2. A sentinel file, once the data file is completely written.

LedgerSync-Batch treats the **sentinel as the trigger** (FR-002). A data file without a sentinel is
never read, however long it has been present.

### Naming

| File | Pattern | Example |
|---|---|---|
| Data | `txn-<YYYYMMDD>-<seq>.csv.pgp` | `txn-20261016-01.csv.pgp` |
| Sentinel | `<data file name>.done` | `txn-20261016-01.csv.pgp.done` |

`<YYYYMMDD>` is the business date the file covers. `<seq>` allows a corrected resend for the same
date (`-02`, `-03`), which the consumer treats as a new file under FR-007.

### Sentinel contents

The sentinel MAY be empty. If non-empty, it SHOULD contain the data file's byte size on a single
line, which the consumer uses as a cheap sanity check before decrypting.

### Consumer obligations

- Never read a data file lacking its sentinel (FR-002).
- Alert if a sentinel names a data file that is absent or unreadable (FR-002a).
- Alert if no completed delivery has arrived for a business date by its deadline (FR-002b).
- Archive both files on any terminal outcome (R10).

### Producer obligations

- Write the sentinel only after the data file is closed and flushed.
- Never modify a data file after its sentinel exists.
- Never reuse a `<seq>` for a different file on the same business date.

---

## 2. Encryption

| Property | Value |
|---|---|
| Scheme | OpenPGP (RFC 4880) |
| Recipient key | LedgerSync-Batch public key, distributed out of band |
| Compression | Optional; consumer handles compressed and uncompressed |
| Signing | Optional; if the file is signed, the consumer verifies and rejects on bad signature |

Decryption failure means the run is FAILED with no records read (FR-003).

---

## 3. File format

UTF-8, CSV, comma-delimited, `\n` or `\r\n` line endings, double-quote escaping per RFC 4180.

### Header row (exactly one, first line)

```text
H,<business_date>,<declared_record_count>,<declared_net_total>
```

| Field | Format | Notes |
|---|---|---|
| Record marker | literal `H` | |
| `business_date` | `YYYY-MM-DD` | The date this file settles |
| `declared_record_count` | integer ≥ 0 | Count of detail rows, excluding the header |
| `declared_net_total` | decimal, 2 dp, may be negative | Signed sum of all detail rows |

A missing or unparseable header fails the run (FR-023) — without it the file cannot be proven
correct, and settling on trust is not available.

### Detail rows

```text
D,<account_number>,<amount>,<type>,<timestamp>
```

| Field | Format | Validation | Rule |
|---|---|---|---|
| Record marker | literal `D` | must be `D` | FR-008 |
| `account_number` | 1–34 chars, alphanumeric | must exist and be active | FR-009 |
| `amount` | decimal, ≤ 2 dp, non-negative | numeric, precision within currency | FR-010 |
| `type` | `CREDIT` or `DEBIT` | member of the configured set | FR-011 |
| `timestamp` | ISO-8601 with offset | parseable, within `business_date` | FR-012 |

`amount` is always non-negative; `type` supplies the direction. The consumer derives
`signed_amount` as `+amount` for `CREDIT` and `-amount` for `DEBIT`, and it is this signed value
that `declared_net_total` must equal in sum.

### Example

```text
H,2026-10-16,3,-150.00
D,GB29NWBK60161331926819,250.00,CREDIT,2026-10-16T09:14:22+01:00
D,GB29NWBK60161331926819,300.00,DEBIT,2026-10-16T11:02:05+01:00
D,GB94BARC10201530093459,100.00,DEBIT,2026-10-16T16:48:11+01:00
```

`250.00 − 300.00 − 100.00 = −150.00`, matching the header. Three detail rows, matching the count.

---

## 4. Reconciliation rule (the part most likely to surprise the producer)

The consumer compares its observed count and signed sum against the **full** header values,
unreduced by anything it rejected.

**A file containing even one invalid row will not settle.** The run is marked FAILED, every rejected
row is reported, and the file must be corrected and resent. This is deliberate: once a row is
dropped, the file's own control total can no longer prove nothing was lost.

Producers should read this as: the header must describe the file exactly, and the file must be
wholly valid. A partially valid file is not partially settled.

---

## 5. Edge cases

| Case | Consumer behaviour |
|---|---|
| Header declares 0, no detail rows | Valid; reconciles; run SETTLED with no balance change |
| Header present, detail rows absent, count > 0 | Mismatch → FAILED |
| Duplicate byte-identical file | Recognised by fingerprint; ignored, no new run (FR-006) |
| Different file, business date already SETTLED | Held for operator decision (FR-006a) |
| Different file, business date FAILED | Processed as a correction (FR-007) |
| >25% of rows rejected after 1,000 read | Read abandoned; FAILED; alert states the list is partial (FR-015, FR-015a) |
| Timestamp outside `business_date` | Row rejected (FR-012) |

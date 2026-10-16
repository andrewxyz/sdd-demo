# Sample transaction files

Plain-text CSVs, **before encryption**. They follow the partner file contract in
[`contracts/transaction-file.md`](../specs/001-eod-transaction-settlement/contracts/transaction-file.md).

| File | Business date | What it tests | Expected |
|---|---|---|---|
| `01-clean-settles.csv` | 2026-10-16 | A good file | `SETTLED`. Account A 1000.00 to 950.00, account B 500.00 to 400.00 |
| `02-unbalanced-header.csv` | 2026-10-17 | Header says −12500.00, rows add up to −150.00 | `FAILED`, no balance changes, one alert |
| `03-one-bad-row.csv` | 2026-10-18 | One row has no account number | `FAILED`, 1 reject at row 5, no balance changes |
| `04-empty-file.csv` | 2026-10-19 | Zero transactions | `SETTLED`, nothing changes |
| `05-corrected-resend.csv` | 2026-10-17 | A correction for the date 02 failed | `SETTLED`. A 950.00 to 1050.00, B 400.00 to 360.00 |

Each sample uses a **different business date**, so they can be sent one after another without
resetting. Run them in order, because the balances above assume that.

## Sending one

The app only accepts **PGP-encrypted** files with a `.done` marker beside them, so do not copy these
into the landing zone as they are. Use the helper, which encrypts, names the file from the header
date, and writes the marker last:

```bash
scripts/e2e-setup.sh send samples/01-clean-settles.csv
```

The full setup (database, keys, starting the app, seeding the two accounts) is in
[docs/how-to-test.md](../docs/how-to-test.md#a4-tier-3--run-the-live-app-end-to-end).

## Writing your own

- Line 1 is the header: `H,<YYYY-MM-DD>,<number of rows>,<signed net total>`.
- Every other line is `D,<account>,<amount>,<CREDIT|DEBIT>,<ISO-8601 timestamp>`.
- The net total is the signed sum: CREDIT adds, DEBIT subtracts.
- Every timestamp must fall on the header date **in UTC**.
- Accounts must already exist. The two seeded ones are `GB29NWBK60161331926819` and
  `GB94BARC10201530093459`.
- A business date can only be settled once. To re-run a date, reset first:
  `scripts/e2e-setup.sh reset`.

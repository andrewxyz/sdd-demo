#!/usr/bin/env bash
#
# End-to-end harness: generates a throwaway PGP keypair, builds transaction files per
# contracts/transaction-file.md, encrypts them, and drops them into the landing zone in the
# agreed order (data file first, THEN the sentinel).
#
# This exercises the one path no automated test covers: real OpenPGP decryption of a file
# arriving in a real landing zone.
#
#   scripts/e2e-setup.sh keys            # one-off: generate a test keypair
#   scripts/e2e-setup.sh seed            # insert ACTIVE accounts to settle against
#   scripts/e2e-setup.sh drop clean      # drop a built-in fixture and its sentinel
#   scripts/e2e-setup.sh send FILE.csv   # encrypt and drop a CSV of your own (see samples/)
#   scripts/e2e-setup.sh check           # show run status, balances and rejects
#   scripts/e2e-setup.sh reset           # clear landing zone, archive and database rows
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY_DIR="${ROOT}/target/e2e-keys"
LANDING="${ROOT}/landing"
ARCHIVE="${ROOT}/archive"
WORK="${ROOT}/target/e2e-work"
KEY_NAME="ledgersync-e2e"
KEY_UID="LedgerSync E2E <e2e@ledgersync.invalid>"

BUSINESS_DATE="$(date +%Y-%m-%d)"
COMPACT_DATE="$(date +%Y%m%d)"
ACCOUNT_A=GB29NWBK60161331926819
ACCOUNT_B=GB94BARC10201530093459

PSQL="docker exec -i ledgersync-it psql -U ledgersync -d ledgersync -v ON_ERROR_STOP=1"

usage() {
    cat <<USAGE
Usage: $(basename "$0") {keys|seed|drop <fixture>|send <file.csv> [seq]|check|reset}

Fixtures for 'drop':
  clean        Header matches rows            -> SETTLED, no alert
  unbalanced   Header net total is wrong      -> FAILED, RECONCILIATION_MISMATCH
  defects      1000 rows, 7 bad incl. last    -> FAILED, all 7 reported
  hopeless     5000 rows, ~40% malformed      -> FAILED, read abandoned early
  redeliver    Re-drops the last clean file   -> ignored, no new run
  no-sentinel  Data file with no .done        -> never read; DELIVERY_OVERDUE later
USAGE
}

# ---------------------------------------------------------------- keys

keys() {
    mkdir -p "${KEY_DIR}"
    chmod 700 "${KEY_DIR}"
    if [[ -f "${KEY_DIR}/private.asc" ]]; then
        echo "Key already present at ${KEY_DIR}/private.asc"
        return 0
    fi
    # Throwaway key, no passphrase. Test material only — never a production key, and the
    # application reads the passphrase from configuration, which stays empty here.
    #
    # --pinentry-mode loopback with an empty --passphrase is required: without it gpg tries to
    # open a pinentry dialog and fails with "Inappropriate ioctl for device" in a non-interactive
    # shell.
    local gpg_opts=(--homedir "${KEY_DIR}" --no-permission-warning --batch --yes --pinentry-mode loopback --passphrase "")
    gpg "${gpg_opts[@]}" --quick-generate-key "${KEY_UID}" rsa3072 encr never
    gpg "${gpg_opts[@]}" --armor \
        --export-secret-keys "${KEY_UID}" > "${KEY_DIR}/private.asc"
    gpg --homedir "${KEY_DIR}" --no-permission-warning --batch --yes --armor \
        --export "${KEY_UID}" > "${KEY_DIR}/public.asc"
    echo "Generated test keypair in ${KEY_DIR}"
    echo "Start the app with --ledgersync.private-key-path=${KEY_DIR}/private.asc"
}

# ---------------------------------------------------------------- seed

seed() {
    ${PSQL} <<SQL
INSERT INTO customer_balance (account_number, balance, status)
VALUES ('${ACCOUNT_A}', 1000.00, 'ACTIVE'), ('${ACCOUNT_B}', 500.00, 'ACTIVE')
ON CONFLICT (account_number) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE';
SQL
    echo "Seeded ${ACCOUNT_A} = 1000.00 and ${ACCOUNT_B} = 500.00"
}

# ------------------------------------------------------------- fixtures

build_clean() {
    # 250.00 credit - 300.00 debit - 100.00 debit = -150.00
    cat <<CSV
H,${BUSINESS_DATE},3,-150.00
D,${ACCOUNT_A},250.00,CREDIT,${BUSINESS_DATE}T09:14:22+00:00
D,${ACCOUNT_A},300.00,DEBIT,${BUSINESS_DATE}T11:02:05+00:00
D,${ACCOUNT_B},100.00,DEBIT,${BUSINESS_DATE}T16:48:11+00:00
CSV
}

build_unbalanced() {
    # Same rows, header deliberately disagrees.
    cat <<CSV
H,${BUSINESS_DATE},3,-12500.00
D,${ACCOUNT_A},250.00,CREDIT,${BUSINESS_DATE}T09:14:22+00:00
D,${ACCOUNT_A},300.00,DEBIT,${BUSINESS_DATE}T11:02:05+00:00
D,${ACCOUNT_B},100.00,DEBIT,${BUSINESS_DATE}T16:48:11+00:00
CSV
}

build_defects() {
    # 1000 rows, 7 defective, one of them the LAST row. If reading stops at the first defect,
    # the last row never gets reported — that is the assertion that matters.
    local total=1000 placed=0
    echo "H,${BUSINESS_DATE},${total},993.00"
    for ((i = 1; i <= total; i++)); do
        # Note the assignment form: ((placed++)) returns exit 1 when placed is 0, which under
        # `set -e` silently kills the script before it writes a single row.
        if { (( i % 140 == 0 && placed < 6 )) || (( i == total )); }; then
            echo "D,,10.00,CREDIT,${BUSINESS_DATE}T09:00:00+00:00"
            placed=$((placed + 1))
        else
            echo "D,${ACCOUNT_A},1.00,CREDIT,${BUSINESS_DATE}T09:00:00+00:00"
        fi
    done
}

build_hopeless() {
    # ~40% malformed across 5000 rows: the wrong file landing in the zone.
    local total=5000
    echo "H,${BUSINESS_DATE},${total},3000.00"
    for ((i = 1; i <= total; i++)); do
        if (( i % 5 <= 1 )); then
            echo "D,,1.00,CREDIT,${BUSINESS_DATE}T09:00:00+00:00"
        else
            echo "D,${ACCOUNT_A},1.00,CREDIT,${BUSINESS_DATE}T09:00:00+00:00"
        fi
    done
}

# next_seq <YYYYMMDD> <YYYY-MM-DD>: the first sequence number not already used for that business
# date, in the landing zone or the archive. A corrected resend therefore becomes -02 on its own.
next_seq() {
    local compact="$1" dated="$2" n=1
    while [[ -e "${LANDING}/txn-${compact}-$(printf '%02d' ${n}).csv.pgp" ]] \
       || [[ -e "${ARCHIVE}/${dated}/txn-${compact}-$(printf '%02d' ${n}).csv.pgp" ]]; do
        n=$((n + 1))
    done
    printf '%02d' "${n}"
}

# send <file.csv> [seq]: encrypts YOUR csv and drops it, data file first and sentinel second.
# The business date comes from the file's header row, because the filename must carry it.
send() {
    local csv="${1:-}" seq_override="${2:-}"
    [[ -n "${csv}" && -f "${csv}" ]] || { echo "Usage: $(basename "$0") send <file.csv> [seq]" >&2; exit 1; }
    [[ -f "${KEY_DIR}/public.asc" ]] || { echo "Run '$(basename "$0") keys' first." >&2; exit 1; }

    local dated compact seq data
    dated="$(head -1 "${csv}" | cut -d, -f2 | tr -d '[:space:]')"
    [[ "${dated}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
        || { echo "Line 1 of ${csv} is not a header like H,2026-10-16,3,-150.00" >&2; exit 1; }
    compact="${dated//-/}"
    seq="${seq_override:-$(next_seq "${compact}" "${dated}")}"
    data="txn-${compact}-${seq}.csv.pgp"

    mkdir -p "${LANDING}"
    gpg --homedir "${KEY_DIR}" --no-permission-warning --batch --yes --trust-model always \
        --recipient "${KEY_UID}" --encrypt --output "${LANDING}/${data}" "${csv}"
    # Order matters: the sentinel is written only once the data file is complete (FR-002).
    touch "${LANDING}/${data}.done"
    echo "Sent ${csv} as ${data} (business date ${dated}) with its sentinel."
}

drop() {
    local fixture="${1:-clean}"
    mkdir -p "${LANDING}" "${WORK}"
    [[ -f "${KEY_DIR}/public.asc" ]] || { echo "Run '$(basename "$0") keys' first." >&2; exit 1; }

    if [[ "${fixture}" == "redeliver" ]]; then
        local last
        last="$(ls -1t "${ARCHIVE}"/*/*.csv.pgp 2>/dev/null | head -1 || true)"
        [[ -n "${last}" ]] || { echo "Nothing archived to redeliver yet." >&2; exit 1; }
        cp "${last}" "${LANDING}/"
        touch "${LANDING}/$(basename "${last}").done"
        # The ENCRYPTED file is copied back, not re-encrypted. PGP is randomised, so encrypting
        # the same CSV again gives different bytes, a new fingerprint and a different outcome.
        # Redelivery means the very same bytes arriving twice.
        echo "Redelivered $(basename "${last}") — expect it to be ignored by fingerprint."
        return 0
    fi

    local plain="${WORK}/${fixture}.csv"
    case "${fixture}" in
        clean|no-sentinel) build_clean   > "${plain}" ;;
        unbalanced)        build_unbalanced > "${plain}" ;;
        defects)           build_defects > "${plain}" ;;
        hopeless)          build_hopeless > "${plain}" ;;
        *) usage; exit 1 ;;
    esac

    local seq data
    seq="$(next_seq "${COMPACT_DATE}" "${BUSINESS_DATE}")"
    data="txn-${COMPACT_DATE}-${seq}.csv.pgp"

    gpg --homedir "${KEY_DIR}" --no-permission-warning --batch --yes --trust-model always \
        --recipient "${KEY_UID}" --encrypt --output "${LANDING}/${data}" "${plain}"

    if [[ "${fixture}" == "no-sentinel" ]]; then
        echo "Dropped ${data} WITHOUT a sentinel — it must never be read."
        return 0
    fi

    # Order matters: the sentinel is written only once the data file is complete (FR-002).
    touch "${LANDING}/${data}.done"
    echo "Dropped ${data} and its sentinel. The poller picks it up within the poll interval."
}

# --------------------------------------------------------------- check

check() {
    echo "=== Runs ==="
    ${PSQL} -c "SELECT execution_id, source_file_name, status, records_read, records_rejected,
                       records_applied, declared_net_total, observed_net_total, early_stopped
                  FROM settlement_run ORDER BY started_at;"
    echo "=== Balances ==="
    ${PSQL} -c "SELECT account_number, balance, last_settled_at FROM customer_balance ORDER BY 1;"
    echo "=== Rejected rows (first 10) ==="
    ${PSQL} -c "SELECT source_row_number, failing_field, rejection_reason, raw_row_excerpt
                  FROM settlement_rejected_record ORDER BY source_row_number LIMIT 10;"
}

reset() {
    rm -rf "${LANDING}" "${ARCHIVE}" "${WORK}"
    ${PSQL} <<'SQL'
DELETE FROM settlement_rejected_record;
DELETE FROM settlement_staged_transaction;
DELETE FROM settlement_run;
DELETE FROM customer_balance;
SQL
    echo "Landing zone, archive and database cleared. Keys kept."
}

case "${1:-}" in
    keys)  keys ;;
    seed)  seed ;;
    drop)  shift; drop "${1:-clean}" ;;
    send)  shift; send "${1:-}" "${2:-}" ;;
    check) check ;;
    reset) reset ;;
    *)     usage; exit 1 ;;
esac

package com.ledgersync.batch.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.regex.Pattern;

import com.ledgersync.batch.domain.ActiveAccountDirectory;
import com.ledgersync.batch.domain.TransactionRecord;
import com.ledgersync.batch.domain.ValidationOutcome;

/**
 * FR-008 to FR-012. Pure Java: no I/O, no Spring, no database — constructible in a test with a
 * lambda and a set (Constitution II, Development Workflow).
 */
public class RecordValidator {

    /** contracts/transaction-file.md: 1–34 alphanumeric characters. */
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[A-Za-z0-9]{1,34}$");

    /** FR-010: the settlement currency has a two-digit minor unit. */
    private static final int MAX_SCALE = 2;

    private final ActiveAccountDirectory accountDirectory;
    private final Set<String> settlementTypes;

    public RecordValidator(ActiveAccountDirectory accountDirectory, Set<String> settlementTypes) {
        this.accountDirectory = accountDirectory;
        this.settlementTypes = Set.copyOf(settlementTypes);
    }

    /**
     * @param businessDate the date the file declares; a row dated elsewhere is rejected (FR-012)
     */
    public ValidationOutcome validate(TransactionRecord record, LocalDate businessDate) {
        // FR-008: all four fields present and well-formed.
        if (record.accountNumber() == null || record.accountNumber().isBlank()) {
            return ValidationOutcome.rejected("accountNumber", "Account number is missing");
        }
        if (!ACCOUNT_NUMBER.matcher(record.accountNumber()).matches()) {
            return ValidationOutcome.rejected("accountNumber",
                    "Account number must be 1 to 34 alphanumeric characters");
        }
        // FR-009: recognised and active.
        if (!accountDirectory.isActive(record.accountNumber())) {
            return ValidationOutcome.rejected("accountNumber",
                    "Account is not recognised or is not active");
        }

        // FR-010: numeric, non-negative, at most 2 decimal places. Never silently rounded —
        // rounding money without telling anyone is how a reconciliation break becomes untraceable.
        BigDecimal amount = record.amount();
        if (amount == null) {
            return ValidationOutcome.rejected("amount", "Amount is missing");
        }
        if (amount.signum() < 0) {
            return ValidationOutcome.rejected("amount",
                    "Amount must not be negative; direction is given by the transaction type");
        }
        if (amount.stripTrailingZeros().scale() > MAX_SCALE) {
            return ValidationOutcome.rejected("amount",
                    "Amount has more than 2 decimal places, which the settlement currency does not support");
        }

        // FR-011: within the configured, contract-bound set.
        if (record.type() == null || !settlementTypes.contains(record.type())) {
            return ValidationOutcome.rejected("type",
                    "Transaction type '%s' is not a settlement type".formatted(record.type()));
        }

        // FR-012: parseable and within the file's business date.
        if (record.timestamp() == null) {
            return ValidationOutcome.rejected("timestamp", "Timestamp is missing");
        }
        LocalDate rowDate = record.timestamp().atOffset(ZoneOffset.UTC).toLocalDate();
        if (!rowDate.equals(businessDate)) {
            return ValidationOutcome.rejected("timestamp",
                    "Timestamp falls on %s but the file declares business date %s"
                            .formatted(rowDate, businessDate));
        }

        return ValidationOutcome.accepted();
    }
}

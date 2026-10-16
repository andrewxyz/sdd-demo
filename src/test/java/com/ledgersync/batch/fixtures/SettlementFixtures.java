package com.ledgersync.batch.fixtures;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds transaction files per contracts/transaction-file.md section 3.
 *
 * <p>Generated rather than checked in, so a fixture cannot silently drift from the contract it is
 * supposed to represent — and so a 500,000-row throughput fixture does not live in git.
 */
public final class SettlementFixtures {

    public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 16);
    public static final String ACCOUNT_A = "GB29NWBK60161331926819";
    public static final String ACCOUNT_B = "GB94BARC10201530093459";

    private final List<String> rows = new ArrayList<>();
    private BigDecimal runningTotal = BigDecimal.ZERO;
    private long rowCount;

    private LocalDate businessDate = BUSINESS_DATE;
    private Long declaredCountOverride;
    private BigDecimal declaredTotalOverride;

    public static SettlementFixtures file() {
        return new SettlementFixtures();
    }

    public SettlementFixtures on(LocalDate date) {
        this.businessDate = date;
        return this;
    }

    public SettlementFixtures credit(String account, String amount) {
        return detail(account, amount, "CREDIT", businessDate + "T09:14:22+00:00");
    }

    public SettlementFixtures debit(String account, String amount) {
        return detail(account, amount, "DEBIT", businessDate + "T11:02:05+00:00");
    }

    public SettlementFixtures detail(String account, String amount, String type, String timestamp) {
        rows.add("D,%s,%s,%s,%s".formatted(account, amount, type, timestamp));
        BigDecimal value = new BigDecimal(amount);
        runningTotal = "DEBIT".equals(type)
                ? runningTotal.subtract(value)
                : runningTotal.add(value);
        rowCount++;
        return this;
    }

    /** A row that cannot be parsed or validated — used to exercise the reject path. */
    public SettlementFixtures malformed(String rawRow) {
        rows.add(rawRow);
        rowCount++;
        return this;
    }

    /** Makes the header disagree with the rows, so reconciliation must fail (FR-021). */
    public SettlementFixtures declaringTotal(String total) {
        this.declaredTotalOverride = new BigDecimal(total);
        return this;
    }

    public SettlementFixtures declaringCount(long count) {
        this.declaredCountOverride = count;
        return this;
    }

    public Path writeTo(Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName);
        List<String> lines = new ArrayList<>();
        lines.add("H,%s,%d,%s".formatted(businessDate, declaredCount(), declaredTotal()));
        lines.addAll(rows);
        Files.write(target, lines);
        return target;
    }

    public long declaredCount() {
        return declaredCountOverride == null ? rowCount : declaredCountOverride;
    }

    public String declaredTotal() {
        BigDecimal total = declaredTotalOverride == null ? runningTotal : declaredTotalOverride;
        // HALF_UP, not UNNECESSARY: fixtures deliberately contain over-precise amounts to
        // exercise FR-010, and a fixture builder must not blow up producing a header for a file
        // whose rows are supposed to be invalid.
        return total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Valid rows spread with {@code defectCount} defects, one of them on the very last row. */
    public static SettlementFixtures withDefectsIncludingLastRow(int totalRows, int defectCount) {
        SettlementFixtures fixture = file();
        int defectsPlaced = 0;
        for (int i = 1; i <= totalRows; i++) {
            boolean lastRow = i == totalRows;
            boolean placeDefect = lastRow
                    ? defectsPlaced < defectCount
                    : defectsPlaced < defectCount - 1 && i % Math.max(1, totalRows / defectCount) == 0;
            if (placeDefect) {
                // A defect on the last row is the assertion that matters: if reading stopped at
                // the first defect, this row is never reported.
                fixture.malformed("D,,10.00,CREDIT," + BUSINESS_DATE + "T09:00:00+00:00");
                defectsPlaced++;
            } else {
                fixture.credit(ACCOUNT_A, "1.00");
            }
        }
        return fixture;
    }
}

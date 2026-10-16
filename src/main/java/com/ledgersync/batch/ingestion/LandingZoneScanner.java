package com.ledgersync.batch.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.ledgersync.batch.config.SettlementProperties;
import com.ledgersync.batch.ingestion.dto.DeliveryCandidate;
import com.ledgersync.batch.ingestion.dto.Eligibility;
import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.repository.SettlementRunRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds complete deliveries in the landing zone and decides what to do with each.
 *
 * <p><strong>The sentinel is the trigger (FR-002).</strong> A data file without
 * {@code <name>.done} is never read, however long it has been present — the absence of the
 * sentinel is what holds a mid-upload file back.
 */
@Component
public class LandingZoneScanner {

    /** contracts/transaction-file.md section 1: {@code txn-<YYYYMMDD>-<seq>.csv.pgp}. */
    private static final Pattern DATA_FILE =
            Pattern.compile("^txn-(\\d{8})-(\\d+)\\.csv\\.pgp$");

    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SENTINEL_SUFFIX = ".done";

    private static final Logger log = LoggerFactory.getLogger(LandingZoneScanner.class);

    private final SettlementProperties properties;
    private final ContentFingerprinter fingerprinter;
    private final SettlementRunRepository runRepository;

    public LandingZoneScanner(
            SettlementProperties properties,
            ContentFingerprinter fingerprinter,
            SettlementRunRepository runRepository) {
        this.properties = properties;
        this.fingerprinter = fingerprinter;
        this.runRepository = runRepository;
    }

    /**
     * Complete deliveries, oldest business date first.
     *
     * <p>FR-025c: the ordering is not cosmetic. Settling Tuesday before Monday would produce
     * balances that depend on arrival order rather than on the ledger.
     */
    public List<DeliveryCandidate> scan() throws IOException {
        Path zone = properties.landingZone();
        if (!Files.isDirectory(zone)) {
            log.warn("Landing zone {} does not exist; nothing to scan", zone);
            return List.of();
        }

        List<DeliveryCandidate> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(zone)) {
            List<Path> sentinels = entries
                    .filter(p -> p.getFileName().toString().endsWith(SENTINEL_SUFFIX))
                    .toList();

            for (Path sentinel : sentinels) {
                String sentinelName = sentinel.getFileName().toString();
                String dataName =
                        sentinelName.substring(0, sentinelName.length() - SENTINEL_SUFFIX.length());
                Path dataFile = zone.resolve(dataName);

                // FR-002a: a sentinel naming an absent or unreadable file is a broken delivery,
                // not a late one. Surfaced to the caller as an exception-worthy condition.
                if (!Files.isReadable(dataFile)) {
                    throw new BrokenDeliveryException(dataName);
                }

                LocalDate businessDate = businessDateOf(dataName);
                if (businessDate == null) {
                    throw new BrokenDeliveryException(
                            dataName + " does not match the agreed naming pattern");
                }

                candidates.add(new DeliveryCandidate(
                        dataFile, sentinel, businessDate, fingerprinter.fingerprint(dataFile)));
            }
        }

        candidates.sort(Comparator.comparing(DeliveryCandidate::businessDate)
                .thenComparing(c -> c.dataFile().getFileName().toString()));
        return candidates;
    }

    /**
     * Clarification Q4: fingerprint and business date answer different questions, so they are two
     * rules rather than one. Collapsing them is what would lock out the corrected-file path.
     */
    public Eligibility assess(DeliveryCandidate candidate) {
        if (runRepository.existsByContentFingerprint(candidate.contentFingerprint())) {
            log.info("Redelivery of an already-processed file; no new run will be created");
            return Eligibility.DUPLICATE_IGNORED;
        }
        if (runRepository.existsByBusinessDateAndStatus(
                candidate.businessDate(), RunStatus.SETTLED)) {
            return Eligibility.SETTLED_DATE_CONFLICT;
        }
        // New fingerprint, date open or previously FAILED: process as a correction (FR-007).
        return Eligibility.PROCESS;
    }

    static LocalDate businessDateOf(String dataFileName) {
        Matcher matcher = DATA_FILE.matcher(dataFileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1), BUSINESS_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

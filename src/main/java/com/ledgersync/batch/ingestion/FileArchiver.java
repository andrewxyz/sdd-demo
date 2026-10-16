package com.ledgersync.batch.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

import com.ledgersync.batch.config.SettlementProperties;
import com.ledgersync.batch.ingestion.dto.DeliveryCandidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves the encrypted source file and its sentinel to a dated archive on any terminal outcome
 * (research R10).
 *
 * <p>Deleting the source would destroy evidence a reconciliation dispute needs; leaving it in the
 * landing zone makes the zone ambiguous and invites reprocessing confusion. Archiving keeps both
 * properties.
 */
@Component
public class FileArchiver {

    private static final Logger log = LoggerFactory.getLogger(FileArchiver.class);

    private final SettlementProperties properties;

    public FileArchiver(SettlementProperties properties) {
        this.properties = properties;
    }

    public void archive(DeliveryCandidate candidate) {
        Path target = properties.archive().resolve(candidate.businessDate().toString());
        try {
            Files.createDirectories(target);
            move(candidate.dataFile(), target);
            move(candidate.sentinelFile(), target);
        } catch (IOException e) {
            // Archiving failure must not change the ledger outcome, which is already decided.
            log.error("Could not archive {}; it remains in the landing zone",
                    candidate.dataFile().getFileName(), e);
        }
    }

    private void move(Path source, Path targetDir) throws IOException {
        if (source != null && Files.exists(source)) {
            Files.move(source, targetDir.resolve(source.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Used by the deadline monitor to tell a quiet night from an already-archived delivery. */
    public boolean isArchived(LocalDate businessDate) {
        Path dated = properties.archive().resolve(businessDate.toString());
        return Files.isDirectory(dated);
    }
}

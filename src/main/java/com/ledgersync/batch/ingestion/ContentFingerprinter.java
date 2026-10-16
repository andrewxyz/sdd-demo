package com.ledgersync.batch.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * FR-006 / research R8: streaming SHA-256 over the <em>encrypted</em> file bytes.
 *
 * <p>Fingerprinting before decryption means a redelivered file is recognised without the cost of
 * decrypting it. SHA-256 rather than a faster digest because this value guards the question "have
 * these transactions already been applied" — a collision-prone digest is not a trade worth making
 * against a ledger.
 */
@Component
public class ContentFingerprinter {

    private static final int BUFFER_BYTES = 64 * 1024;

    public String fingerprint(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file);
                DigestInputStream digesting = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            // Streamed: a 500,000-row file is never held in memory to be hashed.
            while (digesting.read(buffer) != -1) {
                // digest updated as a side effect of reading
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}

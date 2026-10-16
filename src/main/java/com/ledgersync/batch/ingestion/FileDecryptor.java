package com.ledgersync.batch.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Iterator;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-003 / research R4: streaming OpenPGP decryption of the partner file.
 *
 * <p>Two properties matter here beyond "it decrypts":
 *
 * <ul>
 *   <li><strong>Streamed, not buffered.</strong> A 500,000-row file is written straight to a
 *       temporary file rather than held in memory.
 *   <li><strong>The plaintext is always deleted.</strong> {@link #deleteQuietly(Path)} runs from a
 *       {@code finally} path in the caller regardless of outcome (research R10). Plaintext
 *       financial data left at rest would fail Constitution Principle I, and the happy path is
 *       exactly the case you cannot rely on.
 * </ul>
 *
 * <p>Key material is supplied at runtime through the organisation's existing key-management
 * arrangement; distribution is out of scope for this feature.
 */
@Component
public class FileDecryptor {

    private static final Logger log = LoggerFactory.getLogger(FileDecryptor.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Decrypts {@code encryptedFile} to a new temporary file and returns its path. The caller owns
     * the result and MUST delete it via {@link #deleteQuietly(Path)} in a finally block.
     */
    public Path decryptToTempFile(Path encryptedFile, Path privateKeyFile, char[] passphrase) {
        Path plaintext = null;
        try {
            plaintext = Files.createTempFile("ledgersync-", ".csv");
            try (InputStream encrypted = PGPUtil.getDecoderStream(Files.newInputStream(encryptedFile));
                    OutputStream out = Files.newOutputStream(plaintext)) {
                decrypt(encrypted, out, privateKeyFile, passphrase);
            }
            return plaintext;
        } catch (IOException | PGPException e) {
            deleteQuietly(plaintext);
            throw new DecryptionFailedException(
                    "Unable to decrypt " + encryptedFile.getFileName(), e);
        }
    }

    private void decrypt(InputStream encrypted, OutputStream out, Path privateKeyFile,
            char[] passphrase) throws IOException, PGPException {
        PGPObjectFactory factory =
                new PGPObjectFactory(encrypted, new JcaKeyFingerprintCalculator());
        Object first = factory.nextObject();
        PGPEncryptedDataList encryptedList = first instanceof PGPEncryptedDataList list
                ? list
                : (PGPEncryptedDataList) factory.nextObject();

        PGPSecretKeyRingCollection secretKeys;
        try (InputStream keyIn = PGPUtil.getDecoderStream(Files.newInputStream(privateKeyFile))) {
            secretKeys = new PGPSecretKeyRingCollection(keyIn, new JcaKeyFingerprintCalculator());
        }

        PGPPublicKeyEncryptedData encryptedData = null;
        PGPPrivateKey privateKey = null;
        Iterator<?> it = encryptedList.getEncryptedDataObjects();
        while (privateKey == null && it.hasNext()) {
            PGPPublicKeyEncryptedData candidate = (PGPPublicKeyEncryptedData) it.next();
            PGPSecretKey secretKey = secretKeys.getSecretKey(candidate.getKeyID());
            if (secretKey != null) {
                privateKey = secretKey.extractPrivateKey(
                        new JcePBESecretKeyDecryptorBuilder()
                                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                .build(passphrase));
                encryptedData = candidate;
            }
        }
        if (privateKey == null) {
            throw new DecryptionFailedException(
                    "No matching private key for any recipient of this file");
        }

        try (InputStream clear = encryptedData.getDataStream(
                new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(privateKey))) {
            Object message = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator())
                    .nextObject();
            // Compression is optional in the contract; handle both shapes.
            if (message instanceof PGPCompressedData compressed) {
                message = new PGPObjectFactory(
                        compressed.getDataStream(), new JcaKeyFingerprintCalculator()).nextObject();
            }
            if (!(message instanceof PGPLiteralData literal)) {
                throw new DecryptionFailedException(
                        "Decrypted payload was not literal data: " + message.getClass().getName());
            }
            literal.getInputStream().transferTo(out);
        }
    }

    /** Deletes the decrypted temporary file. Never throws — this runs on the failure path too. */
    public void deleteQuietly(Path plaintext) {
        if (plaintext == null) {
            return;
        }
        try {
            Files.deleteIfExists(plaintext);
        } catch (IOException e) {
            // Logged, not thrown: failing here would mask the original outcome.
            log.error("Could not delete decrypted temporary file; plaintext may remain at rest", e);
        }
    }
}

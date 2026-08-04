package org.jboss.windup.engine.discovery;

import org.jboss.windup.model.FileModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility class for computing SHA-1 and MD5 hashes of files.
 * <p>
 * Not CDI-managed -- use the static methods directly.
 */
public final class HashCalculator {

    private static final int BUFFER_SIZE = 8192;

    private HashCalculator() {
        // utility class
    }

    /**
     * Computes SHA-1 and MD5 hashes for the given file and sets them on the {@link FileModel}.
     *
     * @param fileModel the file model to update with hash values
     * @throws IOException if an I/O error occurs reading the file
     */
    public static void computeHashes(FileModel fileModel) throws IOException {
        Path path = fileModel.getFilePath();
        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = Files.newInputStream(path)) {
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    sha1.update(buffer, 0, bytesRead);
                    md5.update(buffer, 0, bytesRead);
                }
            }

            fileModel.setSha1Hash(HexFormat.of().formatHex(sha1.digest()));
            fileModel.setMd5Hash(HexFormat.of().formatHex(md5.digest()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 and MD5 are guaranteed to be available in every JVM
            throw new AssertionError("Required digest algorithm not available", e);
        }
    }

    /**
     * Computes the SHA-1 hash of the given file.
     *
     * @param path the file to hash
     * @return the hex-encoded SHA-1 hash
     * @throws IOException if an I/O error occurs reading the file
     */
    public static String computeSha1(Path path) throws IOException {
        return computeDigest(path, "SHA-1");
    }

    /**
     * Computes the MD5 hash of the given file.
     *
     * @param path the file to hash
     * @return the hex-encoded MD5 hash
     * @throws IOException if an I/O error occurs reading the file
     */
    public static String computeMd5(Path path) throws IOException {
        return computeDigest(path, "MD5");
    }

    private static String computeDigest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = Files.newInputStream(path)) {
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Required digest algorithm not available", e);
        }
    }
}

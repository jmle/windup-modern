package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Identifies unknown JARs by SHA-1 lookup against a pre-built sorted text index.
 * The index file ({@code maven-index.txt}) contains lines of the form
 * {@code <sha1-hex> <groupId>:<artifactId>:<packaging>:<version>}, sorted by hash.
 * Lookup uses binary search via {@link java.io.RandomAccessFile} for O(log n) performance.
 */
public class MavenShaIndex {

    private static final Logger LOG = LoggerFactory.getLogger(MavenShaIndex.class);

    private final Path indexPath;

    public MavenShaIndex(Path indexPath) {
        if (Files.isDirectory(indexPath)) {
            this.indexPath = indexPath.resolve("maven-index.txt");
        } else {
            this.indexPath = indexPath;
        }
    }

    public Optional<MavenCoordinates> lookup(Path jarFile) {
        if (!Files.exists(indexPath)) {
            LOG.debug("Maven SHA index not found at {}", indexPath);
            return Optional.empty();
        }

        try {
            String sha1 = computeSha1(jarFile);
            return searchIndex(sha1);
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warn("Failed to compute SHA1 for {}: {}", jarFile, e.getMessage());
            return Optional.empty();
        }
    }

    Optional<MavenCoordinates> searchIndex(String sha1) {
        try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "r")) {
            long fileLength = raf.length();
            long low = 0;
            long high = fileLength;

            while (low < high) {
                long mid = (low + high) / 2;

                raf.seek(mid);
                if (mid > 0) {
                    raf.seek(mid - 1);
                    int prevByte = raf.read();
                    if (prevByte != '\n') {
                        raf.readLine();
                    }
                }

                long lineStart = raf.getFilePointer();
                if (lineStart >= fileLength) break;

                String line = raf.readLine();
                if (line == null) break;

                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) {
                    low = raf.getFilePointer();
                    continue;
                }

                String lineSha = line.substring(0, spaceIdx);
                int cmp = sha1.compareTo(lineSha);

                if (cmp == 0) {
                    String coords = line.substring(spaceIdx + 1).trim();
                    return parseCoordinates(coords);
                } else if (cmp < 0) {
                    high = mid;
                } else {
                    low = raf.getFilePointer();
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to search Maven SHA index: {}", e.getMessage());
        }

        return Optional.empty();
    }

    static Optional<MavenCoordinates> parseCoordinates(String coords) {
        String[] parts = coords.split(":");
        if (parts.length < 3) return Optional.empty();

        String groupId = parts[0];
        String artifactId = parts[1];
        String packaging = parts.length > 2 ? parts[2] : "jar";
        String classifier = parts.length > 3 ? parts[3] : null;
        String version = parts.length > 4 ? parts[4] : (parts.length > 3 ? parts[3] : "");

        if (classifier != null && classifier.isEmpty()) classifier = null;
        if ("jar".equals(classifier) || packaging.equals(classifier)) {
            version = classifier;
            classifier = null;
        }

        return Optional.of(new MavenCoordinates(groupId, artifactId, version, packaging, classifier));
    }

    static String computeSha1(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        return HexFormat.of().formatHex(hash);
    }

    public record MavenCoordinates(String groupId, String artifactId, String version,
                                    String packaging, String classifier) {
        public String name() {
            return groupId + "." + artifactId;
        }
    }
}

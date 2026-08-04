package org.jboss.windup.engine.archive;

import org.jboss.windup.model.ArchiveModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility that identifies known libraries by reading META-INF/MANIFEST.MF from
 * archive files. Extracts Bundle-SymbolicName, Implementation-Title,
 * Implementation-Version, and related attributes to populate the identification
 * fields on {@link ArchiveModel}.
 */
public final class ArchiveIdentifier {

    private static final Logger LOG = Logger.getLogger(ArchiveIdentifier.class.getName());

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    private ArchiveIdentifier() {
        // utility class
    }

    /**
     * Attempts to identify an archive by reading its MANIFEST.MF. If the
     * manifest contains enough metadata, the archive model's identification
     * fields are populated and {@code identified} is set to {@code true}.
     *
     * @param archive the archive model to identify
     */
    public static void identify(ArchiveModel archive) {
        Path archivePath = archive.getFilePath();
        if (archivePath == null || !Files.isRegularFile(archivePath)) {
            return;
        }

        try (InputStream fileIn = Files.newInputStream(archivePath);
             ZipInputStream zis = new ZipInputStream(fileIn)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST_PATH.equals(entry.getName())) {
                    parseManifest(archive, zis);
                    return;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Unable to read manifest from archive: " + archivePath, e);
        }
    }

    private static void parseManifest(ArchiveModel archive, InputStream manifestStream) {
        try {
            Manifest manifest = new Manifest(manifestStream);
            Attributes attrs = manifest.getMainAttributes();
            if (attrs == null) {
                return;
            }

            String groupId = resolveGroupId(attrs);
            String artifactId = resolveArtifactId(attrs);
            String version = resolveVersion(attrs);

            if (artifactId != null) {
                archive.setIdentifiedArtifactId(artifactId);
                archive.setIdentified(true);
            }
            if (groupId != null) {
                archive.setIdentifiedGroupId(groupId);
            }
            if (version != null) {
                archive.setIdentifiedVersion(version);
            }

            String vendor = coalesce(
                    attrs.getValue("Bundle-Vendor"),
                    attrs.getValue("Implementation-Vendor"));
            if (vendor != null) {
                archive.setOrganizationName(vendor);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Error parsing MANIFEST.MF", e);
        }
    }

    private static String resolveGroupId(Attributes attrs) {
        // Bundle-SymbolicName often maps to groupId (or groupId.artifactId)
        String symbolicName = attrs.getValue("Bundle-SymbolicName");
        if (symbolicName != null) {
            // Strip any directives (e.g., ";singleton:=true")
            int semicolon = symbolicName.indexOf(';');
            if (semicolon > 0) {
                symbolicName = symbolicName.substring(0, semicolon).trim();
            }
            // Use everything up to the last dot as groupId
            int lastDot = symbolicName.lastIndexOf('.');
            if (lastDot > 0) {
                return symbolicName.substring(0, lastDot);
            }
            return symbolicName;
        }
        return coalesce(
                attrs.getValue("Implementation-Vendor-Id"),
                attrs.getValue("Extension-Name"));
    }

    private static String resolveArtifactId(Attributes attrs) {
        // Bundle-SymbolicName's last segment can serve as artifactId
        String symbolicName = attrs.getValue("Bundle-SymbolicName");
        if (symbolicName != null) {
            int semicolon = symbolicName.indexOf(';');
            if (semicolon > 0) {
                symbolicName = symbolicName.substring(0, semicolon).trim();
            }
            int lastDot = symbolicName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < symbolicName.length() - 1) {
                return symbolicName.substring(lastDot + 1);
            }
            return symbolicName;
        }
        return coalesce(
                attrs.getValue("Implementation-Title"),
                attrs.getValue("Specification-Title"));
    }

    private static String resolveVersion(Attributes attrs) {
        return coalesce(
                attrs.getValue("Bundle-Version"),
                attrs.getValue("Implementation-Version"),
                attrs.getValue("Specification-Version"));
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}

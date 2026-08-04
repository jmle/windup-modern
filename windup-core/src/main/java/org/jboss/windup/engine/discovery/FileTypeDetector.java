package org.jboss.windup.engine.discovery;

import org.jboss.windup.model.ArchiveType;
import org.jboss.windup.model.FileType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Utility class that detects {@link FileType} and {@link ArchiveType} from file extensions.
 * <p>
 * Not CDI-managed -- instantiate directly or use the static methods.
 */
public final class FileTypeDetector {

    private static final Map<String, FileType> EXTENSION_TO_FILE_TYPE = Map.ofEntries(
            Map.entry("java", FileType.JAVA_SOURCE),
            Map.entry("class", FileType.JAVA_CLASS),
            Map.entry("xml", FileType.XML),
            Map.entry("xsd", FileType.XML),
            Map.entry("xsl", FileType.XML),
            Map.entry("xslt", FileType.XML),
            Map.entry("wsdl", FileType.XML),
            Map.entry("tld", FileType.XML),
            Map.entry("yml", FileType.YAML),
            Map.entry("yaml", FileType.YAML),
            Map.entry("properties", FileType.PROPERTIES),
            Map.entry("jsp", FileType.JSP),
            Map.entry("jspx", FileType.JSP),
            Map.entry("xhtml", FileType.XHTML),
            Map.entry("html", FileType.HTML),
            Map.entry("htm", FileType.HTML),
            Map.entry("css", FileType.CSS),
            Map.entry("js", FileType.JAVASCRIPT),
            Map.entry("ts", FileType.JAVASCRIPT),
            Map.entry("sql", FileType.SQL),
            Map.entry("mf", FileType.MANIFEST),
            Map.entry("jar", FileType.ARCHIVE),
            Map.entry("war", FileType.ARCHIVE),
            Map.entry("ear", FileType.ARCHIVE),
            Map.entry("rar", FileType.ARCHIVE),
            Map.entry("sar", FileType.ARCHIVE),
            Map.entry("zip", FileType.ARCHIVE)
    );

    private static final Map<String, ArchiveType> EXTENSION_TO_ARCHIVE_TYPE = Map.of(
            "jar", ArchiveType.JAR,
            "war", ArchiveType.WAR,
            "ear", ArchiveType.EAR,
            "rar", ArchiveType.RAR,
            "sar", ArchiveType.SAR,
            "zip", ArchiveType.ZIP
    );

    private FileTypeDetector() {
        // utility class
    }

    /**
     * Detects the {@link FileType} for the given file path based on its extension.
     *
     * @param path the file path
     * @return the detected file type, or {@link FileType#OTHER} if unknown
     */
    public static FileType detectFileType(Path path) {
        String ext = getExtension(path);
        if (ext.isEmpty()) {
            return FileType.OTHER;
        }
        return EXTENSION_TO_FILE_TYPE.getOrDefault(ext, FileType.OTHER);
    }

    /**
     * Detects the {@link ArchiveType} for the given file path based on its extension.
     *
     * @param path the file path
     * @return the detected archive type, or {@link ArchiveType#OTHER} if unknown
     */
    public static ArchiveType detectArchiveType(Path path) {
        String ext = getExtension(path);
        if (ext.isEmpty()) {
            return ArchiveType.OTHER;
        }
        return EXTENSION_TO_ARCHIVE_TYPE.getOrDefault(ext, ArchiveType.OTHER);
    }

    /**
     * Returns whether the given file path represents an archive based on its extension.
     *
     * @param path the file path
     * @return true if the extension matches a known archive type
     */
    public static boolean isArchive(Path path) {
        String ext = getExtension(path);
        return EXTENSION_TO_ARCHIVE_TYPE.containsKey(ext);
    }

    /**
     * Extracts the lowercase file extension from a path.
     */
    static String getExtension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}

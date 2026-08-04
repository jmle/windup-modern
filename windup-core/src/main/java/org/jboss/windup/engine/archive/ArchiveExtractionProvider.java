package org.jboss.windup.engine.archive;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.model.ArchiveModel;
import org.jboss.windup.model.ArchiveType;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts archive files (JAR, WAR, EAR, ZIP) discovered during file
 * discovery, creates {@link FileModel} entries for extracted contents, and
 * recursively handles nested archives up to a configurable maximum depth.
 */
@ApplicationScoped
public class ArchiveExtractionProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(ArchiveExtractionProvider.class.getName());

    public static final String PROVIDER_ID = "archive-extraction";
    static final int DEFAULT_MAX_DEPTH = 10;
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(".jar", ".war", ".ear", ".rar", ".sar", ".zip");

    @Override
    public RuleProviderMetadata getMetadata() {
        return new RuleProviderMetadata(
                PROVIDER_ID,
                Phase.ARCHIVE_EXTRACTION,
                Set.of(),
                Set.of(),
                Set.of(),
                List.of("file-discovery"),
                List.of()
        );
    }

    @Override
    public List<Rule> getRules() {
        return List.of(new Rule(
                PROVIDER_ID + "-rule",
                this::checkArchivesExist,
                this::extractArchives,
                new RuleMetadata(Phase.ARCHIVE_EXTRACTION)
        ));
    }

    ConditionResult checkArchivesExist(AnalysisRun run) {
        List<ArchiveModel> archives = run.getContext().archives().findAll();
        if (archives.isEmpty()) {
            return ConditionResult.noMatch();
        }
        return ConditionResult.match(archives);
    }

    void extractArchives(AnalysisRun run, ConditionResult matched) {
        int maxDepth = resolveMaxDepth(run);
        Path outputDir = run.getConfiguration().getOutputDirectory();

        // Take a snapshot of archives registered at the start; new ones may be
        // added as nested archives are discovered.
        List<ArchiveModel> toProcess = new ArrayList<>(run.getContext().archives().findAll());

        for (ArchiveModel archive : toProcess) {
            if (run.isCancelled()) {
                break;
            }
            extractArchive(run, archive, outputDir, 0, maxDepth);
        }
    }

    private void extractArchive(AnalysisRun run, ArchiveModel archive, Path outputDir,
                                int currentDepth, int maxDepth) {
        if (currentDepth >= maxDepth) {
            LOG.warning("Maximum archive extraction depth (" + maxDepth
                    + ") reached for: " + archive.getFilePath());
            return;
        }

        Path archivePath = archive.getFilePath();
        if (archivePath == null || !Files.isRegularFile(archivePath)) {
            LOG.fine("Skipping non-existent archive: " + archivePath);
            return;
        }

        Path extractionDir = outputDir.resolve("archives")
                .resolve(sanitizeName(archive.getFileName()));
        try {
            Files.createDirectories(extractionDir);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Cannot create extraction directory: " + extractionDir, e);
            return;
        }

        try (InputStream fileIn = Files.newInputStream(archivePath);
             ZipInputStream zis = new ZipInputStream(fileIn)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (run.isCancelled()) {
                    break;
                }
                processZipEntry(run, archive, zis, entry, extractionDir, currentDepth, maxDepth);
            }

            // After extraction, try to identify the archive via its manifest
            ArchiveIdentifier.identify(archive);

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error extracting archive: " + archivePath, e);
        }
    }

    private void processZipEntry(AnalysisRun run, ArchiveModel parentArchive,
                                 ZipInputStream zis, ZipEntry entry,
                                 Path extractionDir, int currentDepth, int maxDepth) throws IOException {
        String entryName = entry.getName();
        // Guard against zip-slip
        Path resolved = extractionDir.resolve(entryName).normalize();
        if (!resolved.startsWith(extractionDir)) {
            LOG.warning("Skipping zip-slip entry: " + entryName);
            return;
        }

        if (entry.isDirectory()) {
            Files.createDirectories(resolved);
            FileModel dirModel = new FileModel(resolved);
            dirModel.setDirectory(true);
            dirModel.setFileType(FileType.DIRECTORY);
            run.getContext().files().register(dirModel);
            parentArchive.getEntries().add(dirModel);
        } else {
            // Ensure parent directories exist
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            writeEntryToFile(zis, resolved);

            if (isArchiveFile(entryName)) {
                ArchiveModel nestedArchive = new ArchiveModel(resolved);
                nestedArchive.setArchiveType(determineArchiveType(entryName));
                nestedArchive.setFileSize(Files.size(resolved));
                run.getContext().files().register(nestedArchive);
                run.getContext().archives().register(nestedArchive);
                parentArchive.getEntries().add(nestedArchive);

                // Recurse into nested archive
                extractArchive(run, nestedArchive, extractionDir, currentDepth + 1, maxDepth);
            } else {
                FileModel fileModel = new FileModel(resolved);
                fileModel.setFileType(classifyFile(entryName));
                fileModel.setFileSize(Files.size(resolved));
                run.getContext().files().register(fileModel);
                parentArchive.getEntries().add(fileModel);
            }
        }
    }

    private static void writeEntryToFile(ZipInputStream zis, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            zis.transferTo(out);
        }
    }

    static boolean isArchiveFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    static ArchiveType determineArchiveType(String fileName) {
        if (fileName == null) return ArchiveType.OTHER;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return ArchiveType.JAR;
        if (lower.endsWith(".war")) return ArchiveType.WAR;
        if (lower.endsWith(".ear")) return ArchiveType.EAR;
        if (lower.endsWith(".rar")) return ArchiveType.RAR;
        if (lower.endsWith(".sar")) return ArchiveType.SAR;
        if (lower.endsWith(".zip")) return ArchiveType.ZIP;
        return ArchiveType.OTHER;
    }

    static FileType classifyFile(String fileName) {
        if (fileName == null) return FileType.OTHER;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return FileType.JAVA_SOURCE;
        if (lower.endsWith(".class")) return FileType.JAVA_CLASS;
        if (lower.endsWith(".xml")) return FileType.XML;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return FileType.YAML;
        if (lower.endsWith(".properties")) return FileType.PROPERTIES;
        if (lower.endsWith(".jsp")) return FileType.JSP;
        if (lower.endsWith(".xhtml")) return FileType.XHTML;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return FileType.HTML;
        if (lower.endsWith(".css")) return FileType.CSS;
        if (lower.endsWith(".js")) return FileType.JAVASCRIPT;
        if (lower.endsWith(".sql")) return FileType.SQL;
        if ("META-INF/MANIFEST.MF".equals(fileName) || lower.endsWith("/manifest.mf")) return FileType.MANIFEST;
        return FileType.OTHER;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "unknown";
        // Replace characters that are problematic in file paths
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static int resolveMaxDepth(AnalysisRun run) {
        Object depthOption = run.getConfiguration().getOptions().get("archiveExtractionMaxDepth");
        if (depthOption instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_MAX_DEPTH;
    }
}

package io.konveyor.provider.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Handles JAR, WAR, and EAR archives for analysis. Creates a Maven project structure
 * ({@code src/main/java}, {@code src/main/webapp}, {@code pom.xml}) in the output
 * directory so that both the java provider and the engine's builtin provider can
 * discover and match against the decompiled contents.
 */
public class ArchiveHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveHandler.class);

    private final DecompilerService decompiler;

    public ArchiveHandler(DecompilerService decompiler) {
        this.decompiler = decompiler;
    }

    public ArchiveResult handleArchive(Path archivePath, Path projectDir) {
        String name = archivePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".war")) {
            return handleWar(archivePath, projectDir);
        } else if (name.endsWith(".ear")) {
            return handleEar(archivePath, projectDir);
        } else if (name.endsWith(".jar")) {
            return handleJar(archivePath, projectDir);
        } else {
            LOG.warn("Unknown archive type: {}", archivePath);
            return new ArchiveResult(archivePath, List.of(), List.of());
        }
    }

    private ArchiveResult handleJar(Path jarPath, Path projectDir) {
        Path srcMainJava = projectDir.resolve("src/main/java");
        DecompileResult result = decompiler.decompileJar(jarPath, srcMainJava);
        List<Path> sourceDirs = result.hasOutput() ? List.of(srcMainJava) : List.of();
        return new ArchiveResult(jarPath, sourceDirs, List.of());
    }

    private ArchiveResult handleWar(Path warPath, Path projectDir) {
        try {
            Files.createDirectories(projectDir);
        } catch (IOException e) {
            LOG.error("Failed to create project directory: {}", projectDir, e);
            return new ArchiveResult(warPath, List.of(), List.of());
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("war-exploded");
        } catch (IOException e) {
            LOG.error("Failed to create temp directory for WAR explosion", e);
            return new ArchiveResult(warPath, List.of(), List.of());
        }

        try {
            explode(warPath, tempDir);
        } catch (IOException e) {
            LOG.error("Failed to explode WAR: {}", warPath, e);
            return new ArchiveResult(warPath, List.of(), List.of());
        }

        List<Path> sourceDirs = new ArrayList<>();
        List<Path> dependencyJars = new ArrayList<>();

        Path srcMainJava = projectDir.resolve("src/main/java");

        Path classesDir = tempDir.resolve("WEB-INF/classes");
        if (Files.isDirectory(classesDir)) {
            DecompileResult result = decompileDirectory(classesDir, srcMainJava);
            if (result.hasOutput()) {
                sourceDirs.add(srcMainJava);
            }
            copyNonClassResources(classesDir, srcMainJava);
        }

        Path libDir = tempDir.resolve("WEB-INF/lib");
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> jars = Files.list(libDir)) {
                jars.filter(p -> p.toString().endsWith(".jar"))
                    .sorted()
                    .forEach(dependencyJars::add);
            } catch (IOException e) {
                LOG.warn("Failed to list WEB-INF/lib: {}", libDir, e);
            }
        }

        copyWebResources(tempDir, projectDir.resolve("src/main/webapp"));
        extractPomXml(tempDir, projectDir);

        return new ArchiveResult(warPath, sourceDirs, dependencyJars);
    }

    private ArchiveResult handleEar(Path earPath, Path projectDir) {
        try {
            Files.createDirectories(projectDir);
        } catch (IOException e) {
            LOG.error("Failed to create project directory: {}", projectDir, e);
            return new ArchiveResult(earPath, List.of(), List.of());
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("ear-exploded");
        } catch (IOException e) {
            LOG.error("Failed to create temp directory for EAR explosion", e);
            return new ArchiveResult(earPath, List.of(), List.of());
        }

        try {
            explode(earPath, tempDir);
        } catch (IOException e) {
            LOG.error("Failed to explode EAR: {}", earPath, e);
            return new ArchiveResult(earPath, List.of(), List.of());
        }

        List<Path> sourceDirs = new ArrayList<>();
        List<Path> dependencyJars = new ArrayList<>();

        try (Stream<Path> entries = Files.walk(tempDir)) {
            List<Path> archives = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.toString().toLowerCase();
                        return n.endsWith(".jar") || n.endsWith(".war");
                    })
                    .toList();

            for (Path archive : archives) {
                String n = archive.getFileName().toString().toLowerCase();
                if (n.endsWith(".war")) {
                    ArchiveResult sub = handleWar(archive, projectDir);
                    sourceDirs.addAll(sub.sourceDirs());
                    dependencyJars.addAll(sub.dependencyJars());
                } else {
                    dependencyJars.add(archive);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to walk exploded EAR: {}", tempDir, e);
        }

        extractPomXml(tempDir, projectDir);

        return new ArchiveResult(earPath, sourceDirs, dependencyJars);
    }

    private void copyNonClassResources(Path classesDir, Path targetDir) {
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.toString().endsWith(".class"))
                .forEach(p -> {
                    Path rel = classesDir.relativize(p);
                    Path target = targetDir.resolve(rel.toString());
                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOG.debug("Failed to copy resource: {}", p, e);
                    }
                });
        } catch (IOException e) {
            LOG.warn("Failed to copy non-class resources from {}: {}", classesDir, e.getMessage());
        }
    }

    private void copyWebResources(Path explodedDir, Path webappDir) {
        try (Stream<Path> entries = Files.walk(explodedDir)) {
            entries.filter(Files::isRegularFile)
                .forEach(p -> {
                    String rel = explodedDir.relativize(p).toString();
                    if (rel.startsWith("META-INF")) return;
                    if (rel.startsWith("WEB-INF/classes") || rel.startsWith("WEB-INF/lib")) return;

                    if (rel.startsWith("WEB-INF/")) {
                        copyFile(p, webappDir.resolve(rel));
                        return;
                    }

                    String lower = rel.toLowerCase();
                    if (lower.contains("css") || lower.contains("js") || lower.contains("images") ||
                        lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".jsp")) {
                        copyFile(p, webappDir.resolve(rel));
                    }
                });
        } catch (IOException e) {
            LOG.warn("Failed to copy web resources from {}: {}", explodedDir, e.getMessage());
        }
    }

    private void extractPomXml(Path explodedDir, Path projectDir) {
        Path target = projectDir.resolve("pom.xml");
        if (Files.exists(target)) return;

        Path metaInf = explodedDir.resolve("META-INF");
        if (!Files.isDirectory(metaInf)) return;

        try (Stream<Path> walk = Files.walk(metaInf)) {
            Optional<Path> pomXml = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .findFirst();

            if (pomXml.isPresent()) {
                Files.copy(pomXml.get(), target, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Extracted pom.xml from {}", metaInf.relativize(pomXml.get()));
            }
        } catch (IOException e) {
            LOG.warn("Failed to extract pom.xml from {}: {}", metaInf, e.getMessage());
        }
    }

    private static void copyFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.debug("Failed to copy {} to {}: {}", source, target, e.getMessage());
        }
    }

    private DecompileResult decompileDirectory(Path classesDir, Path outputDir) {
        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(classFiles::add);
        } catch (IOException e) {
            LOG.warn("Failed to walk classes directory: {}", classesDir, e);
            return new DecompileResult(classesDir, outputDir, 0, 1);
        }

        if (classFiles.isEmpty()) {
            return new DecompileResult(classesDir, outputDir, 0, 0);
        }

        Path tempJar = null;
        try {
            tempJar = Files.createTempFile("classes-", ".jar");
            Files.delete(tempJar);
            createJarFromDirectory(classesDir, tempJar);
            return decompiler.decompileJar(tempJar, outputDir);
        } catch (IOException e) {
            LOG.error("Failed to create temp JAR from classes dir: {}", classesDir, e);
            return new DecompileResult(classesDir, outputDir, 0, 1);
        } finally {
            if (tempJar != null) {
                try { Files.deleteIfExists(tempJar); } catch (IOException ignored) {}
            }
        }
    }

    static void explode(Path archivePath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (FileSystem zipFs = FileSystems.newFileSystem(archivePath)) {
            Path root = zipFs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.forEach(entry -> {
                    try {
                        Path target = targetDir.resolve(root.relativize(entry).toString());
                        if (Files.isDirectory(entry)) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        LOG.debug("Failed to extract: {}", entry, e);
                    }
                });
            }
        }
    }

    private static void createJarFromDirectory(Path sourceDir, Path jarPath) throws IOException {
        try (FileSystem zipFs = FileSystems.newFileSystem(jarPath, Map.of("create", "true"))) {
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                walk.filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Path entryPath = zipFs.getPath(sourceDir.relativize(file).toString());
                            if (entryPath.getParent() != null) {
                                Files.createDirectories(entryPath.getParent());
                            }
                            Files.copy(file, entryPath, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            LOG.debug("Failed to add to JAR: {}", file, e);
                        }
                    });
            }
        }
    }

    private static String stripExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public record ArchiveResult(Path archivePath, List<Path> sourceDirs, List<Path> dependencyJars) {}
}

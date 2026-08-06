package io.konveyor.provider.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Handles JAR, WAR, and EAR archives for analysis. JARs are decompiled directly. WARs
 * are exploded to decompile {@code WEB-INF/classes} and collect {@code WEB-INF/lib/*.jar}
 * as dependencies. EARs are exploded and contained JARs/WARs are processed recursively.
 */
public class ArchiveHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveHandler.class);

    private final DecompilerService decompiler;

    public ArchiveHandler(DecompilerService decompiler) {
        this.decompiler = decompiler;
    }

    public ArchiveResult handleArchive(Path archivePath, Path workDir) {
        String name = archivePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".war")) {
            return handleWar(archivePath, workDir);
        } else if (name.endsWith(".ear")) {
            return handleEar(archivePath, workDir);
        } else if (name.endsWith(".jar")) {
            return handleJar(archivePath, workDir);
        } else {
            LOG.warn("Unknown archive type: {}", archivePath);
            return new ArchiveResult(archivePath, List.of(), List.of());
        }
    }

    private ArchiveResult handleJar(Path jarPath, Path workDir) {
        Path outputDir = workDir.resolve("decompiled");
        DecompileResult result = decompiler.decompileJar(jarPath, outputDir);
        List<Path> sourceDirs = result.hasOutput() ? List.of(outputDir) : List.of();
        return new ArchiveResult(jarPath, sourceDirs, List.of());
    }

    private ArchiveResult handleWar(Path warPath, Path workDir) {
        Path explodedDir = workDir.resolve("exploded");
        try {
            explode(warPath, explodedDir);
        } catch (IOException e) {
            LOG.error("Failed to explode WAR: {}", warPath, e);
            return new ArchiveResult(warPath, List.of(), List.of());
        }

        List<Path> sourceDirs = new ArrayList<>();
        List<Path> dependencyJars = new ArrayList<>();

        Path classesDir = explodedDir.resolve("WEB-INF/classes");
        if (Files.isDirectory(classesDir)) {
            Path decompiledClasses = workDir.resolve("decompiled-classes");
            DecompileResult result = decompileDirectory(classesDir, decompiledClasses);
            if (result.hasOutput()) {
                sourceDirs.add(decompiledClasses);
            }
        }

        Path libDir = explodedDir.resolve("WEB-INF/lib");
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> jars = Files.list(libDir)) {
                jars.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(dependencyJars::add);
            } catch (IOException e) {
                LOG.warn("Failed to list WEB-INF/lib: {}", libDir, e);
            }
        }

        return new ArchiveResult(warPath, sourceDirs, dependencyJars);
    }

    private ArchiveResult handleEar(Path earPath, Path workDir) {
        Path explodedDir = workDir.resolve("exploded");
        try {
            explode(earPath, explodedDir);
        } catch (IOException e) {
            LOG.error("Failed to explode EAR: {}", earPath, e);
            return new ArchiveResult(earPath, List.of(), List.of());
        }

        List<Path> sourceDirs = new ArrayList<>();
        List<Path> dependencyJars = new ArrayList<>();

        try (Stream<Path> entries = Files.walk(explodedDir)) {
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
                    Path subWorkDir = workDir.resolve("war-" + stripExtension(archive));
                    ArchiveResult sub = handleWar(archive, subWorkDir);
                    sourceDirs.addAll(sub.sourceDirs());
                    dependencyJars.addAll(sub.dependencyJars());
                } else {
                    dependencyJars.add(archive);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to walk exploded EAR: {}", explodedDir, e);
        }

        return new ArchiveResult(earPath, sourceDirs, dependencyJars);
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

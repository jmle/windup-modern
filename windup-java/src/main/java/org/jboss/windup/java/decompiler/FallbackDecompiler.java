package org.jboss.windup.java.decompiler;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Default {@link DecompilerService} implementation that uses the JDK's
 * {@code javap} tool as a fallback when no full decompiler (Procyon,
 * Fernflower) is available.
 *
 * <p>{@code javap -c -p} produces a disassembly listing rather than true
 * Java source, but it still exposes class structure, method signatures, field
 * declarations, and bytecode instructions &mdash; enough for many analysis
 * rules to work with.</p>
 */
@ApplicationScoped
public class FallbackDecompiler implements DecompilerService {

    private static final Logger LOG = Logger.getLogger(FallbackDecompiler.class.getName());

    /** Maximum time to wait for a single {@code javap} invocation. */
    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public Optional<String> decompile(Path classFile) {
        if (classFile == null || !Files.isRegularFile(classFile)) {
            return Optional.empty();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "javap", "-c", "-p", classFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warning("javap timed out for " + classFile);
                return Optional.empty();
            }

            if (process.exitValue() != 0) {
                LOG.warning("javap exited with code " + process.exitValue()
                        + " for " + classFile + ": " + output);
                return Optional.empty();
            }

            return output.isBlank() ? Optional.empty() : Optional.of(output);
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.WARNING, "Failed to run javap on " + classFile, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    @Override
    public Map<String, String> decompileArchive(Path archivePath, Path outputDir) {
        Map<String, String> results = new LinkedHashMap<>();

        if (archivePath == null || !Files.isRegularFile(archivePath)) {
            return results;
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot create output directory: " + outputDir, e);
            return results;
        }

        try (FileSystem zipFs = FileSystems.newFileSystem(archivePath)) {
            Path root = zipFs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(entry -> {
                        String className = classNameFromZipEntry(entry.toString());
                        try {
                            // Extract the .class file to a temp location so javap can read it
                            Path tempClass = outputDir.resolve(
                                    entry.toString().substring(1)); // strip leading /
                            Files.createDirectories(tempClass.getParent());
                            Files.copy(entry, tempClass);

                            decompile(tempClass).ifPresent(source -> {
                                results.put(className, source);
                                writeSourceFile(outputDir, className, source);
                            });
                        } catch (IOException e) {
                            LOG.log(Level.FINE,
                                    "Skipping entry " + entry + " in " + archivePath, e);
                        }
                    });
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to open archive: " + archivePath, e);
        }

        return results;
    }

    /**
     * Converts a zip entry path like {@code /com/example/Foo.class} into a
     * fully-qualified class name like {@code com.example.Foo}.
     */
    static String classNameFromZipEntry(String entryPath) {
        String normalized = entryPath;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('/', '.');
    }

    private void writeSourceFile(Path outputDir, String className, String source) {
        try {
            String relativePath = className.replace('.', '/') + ".java";
            Path target = outputDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, source);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write decompiled source for " + className, e);
        }
    }
}

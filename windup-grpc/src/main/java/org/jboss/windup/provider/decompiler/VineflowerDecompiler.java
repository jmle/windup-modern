package org.jboss.windup.provider.decompiler;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Manifest;

public class VineflowerDecompiler implements DecompilerService {

    private static final Logger LOG = LoggerFactory.getLogger(VineflowerDecompiler.class);

    private static final Map<String, Object> OPTIONS = Map.of(
            "mpm", 30,
            "ind", "    ",
            "log", "WARN"
    );

    private final int workerCount;

    public VineflowerDecompiler() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public VineflowerDecompiler(int workerCount) {
        this.workerCount = workerCount;
    }

    @Override
    public DecompileResult decompileJar(Path jarPath, Path outputDir) {
        if (!Files.isRegularFile(jarPath)) {
            LOG.warn("Not a file: {}", jarPath);
            return new DecompileResult(jarPath, outputDir, 0, 1);
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOG.error("Cannot create output directory: {}", outputDir, e);
            return new DecompileResult(jarPath, outputDir, 0, 1);
        }

        DirectoryResultSaver saver = new DirectoryResultSaver(outputDir);
        try {
            Fernflower fernflower = new Fernflower(saver, new HashMap<>(OPTIONS), new Slf4jFernflowerLogger());
            fernflower.addSource(jarPath.toFile());
            fernflower.decompileContext();
            fernflower.clearContext();
        } catch (Exception e) {
            LOG.error("Decompilation failed for {}", jarPath, e);
            return new DecompileResult(jarPath, outputDir, saver.getClassCount(), saver.getErrorCount() + 1);
        }

        LOG.info("Decompiled {} classes from {}", saver.getClassCount(), jarPath.getFileName());
        return new DecompileResult(jarPath, outputDir, saver.getClassCount(), saver.getErrorCount());
    }

    @Override
    public List<DecompileResult> decompileJars(List<Path> jarPaths, Path outputDir) {
        if (jarPaths.isEmpty()) {
            return List.of();
        }

        if (jarPaths.size() == 1) {
            Path jarOutputDir = jarOutputDir(outputDir, jarPaths.get(0));
            return List.of(decompileJar(jarPaths.get(0), jarOutputDir));
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(workerCount, jarPaths.size()),
                new DecompilerThreadFactory());

        List<Future<DecompileResult>> futures = new ArrayList<>();
        for (Path jar : jarPaths) {
            Path jarOutputDir = jarOutputDir(outputDir, jar);
            futures.add(executor.submit(() -> decompileJar(jar, jarOutputDir)));
        }

        executor.shutdown();

        List<DecompileResult> results = new ArrayList<>();
        for (Future<DecompileResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                LOG.error("Decompilation task failed", e.getCause());
            }
        }

        int totalClasses = results.stream().mapToInt(DecompileResult::classCount).sum();
        int totalErrors = results.stream().mapToInt(DecompileResult::errorCount).sum();
        LOG.info("Decompiled {} JARs: {} classes, {} errors", results.size(), totalClasses, totalErrors);

        return results;
    }

    private static Path jarOutputDir(Path baseOutputDir, Path jarPath) {
        String jarName = jarPath.getFileName().toString();
        if (jarName.endsWith(".jar")) {
            jarName = jarName.substring(0, jarName.length() - 4);
        }
        return baseOutputDir.resolve(jarName);
    }

    static class DirectoryResultSaver implements IResultSaver {

        private final Path outputDir;
        private final AtomicInteger classCount = new AtomicInteger();
        private final AtomicInteger errorCount = new AtomicInteger();

        DirectoryResultSaver(Path outputDir) {
            this.outputDir = outputDir;
        }

        int getClassCount() {
            return classCount.get();
        }

        int getErrorCount() {
            return errorCount.get();
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            writeSource(entryName, content);
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
            writeSource(entryName, content);
        }

        private void writeSource(String entryName, String content) {
            if (content == null || content.isEmpty()) {
                errorCount.incrementAndGet();
                return;
            }
            try {
                Path target = outputDir.resolve(entryName);
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                classCount.incrementAndGet();
            } catch (IOException e) {
                errorCount.incrementAndGet();
                LOG.error("Failed to write decompiled source: {}", entryName, e);
            }
        }

        @Override
        public void saveFolder(String path) {
            try {
                Files.createDirectories(outputDir.resolve(path));
            } catch (IOException e) {
                LOG.debug("Could not create folder: {}", path);
            }
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {}

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {}

        @Override
        public void copyFile(String source, String path, String entryName) {}

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {}

        @Override
        public void closeArchive(String path, String archiveName) {}

        @Override
        public void close() {}
    }

    static class Slf4jFernflowerLogger extends IFernflowerLogger {

        Slf4jFernflowerLogger() {
            setSeverity(Severity.WARN);
        }

        @Override
        public void writeMessage(String message, Severity severity) {
            switch (severity) {
                case ERROR -> LOG.error("[Vineflower] {}", message);
                case WARN -> LOG.warn("[Vineflower] {}", message);
                case INFO -> LOG.info("[Vineflower] {}", message);
                default -> LOG.debug("[Vineflower] {}", message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable t) {
            switch (severity) {
                case ERROR -> LOG.error("[Vineflower] {}", message, t);
                case WARN -> LOG.warn("[Vineflower] {}", message, t);
                default -> LOG.debug("[Vineflower] {}", message, t);
            }
        }
    }

    private static class DecompilerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "decompiler-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}

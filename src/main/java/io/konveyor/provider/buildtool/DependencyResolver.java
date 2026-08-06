package io.konveyor.provider.buildtool;

import io.konveyor.provider.decompiler.DecompileResult;
import io.konveyor.provider.decompiler.DecompilerService;
import io.konveyor.provider.decompiler.VineflowerDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Obtains Java source for project dependencies so they can be indexed and analyzed.
 * For dependencies with source JARs, extracts {@code .java} files directly. For those
 * without, decompiles binary JARs via {@link VineflowerDecompiler}. Also supports
 * downloading source JARs through {@code mvn dependency:sources}.
 */
public class DependencyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyResolver.class);

    private final DecompilerService decompiler;

    public DependencyResolver() {
        this(new VineflowerDecompiler());
    }

    public DependencyResolver(DecompilerService decompiler) {
        this.decompiler = decompiler;
    }

    public ResolveResult resolve(List<BuildTool.ResolvedDependency> deps, Path workDir) {
        Path decompiledDir = workDir.resolve("decompiled-deps");
        Path sourcesDir = workDir.resolve("extracted-sources");

        List<Path> sourceDirs = new ArrayList<>();
        List<Path> jarsToDecompile = new ArrayList<>();

        for (BuildTool.ResolvedDependency dep : deps) {
            if (dep.jarPath() == null || !Files.exists(dep.jarPath())) {
                continue;
            }

            if (dep.hasSourceJar()) {
                Path sourceJar = dep.sourceJarPath();
                if (sourceJar != null && Files.exists(sourceJar)) {
                    Path extractDir = sourcesDir.resolve(dep.artifactId() + "-" + dep.version());
                    try {
                        extractSourceJar(sourceJar, extractDir);
                        sourceDirs.add(extractDir);
                        LOG.debug("Extracted sources for {}:{}", dep.groupId(), dep.artifactId());
                    } catch (IOException e) {
                        LOG.warn("Failed to extract source JAR {}, will decompile binary", sourceJar);
                        jarsToDecompile.add(dep.jarPath());
                    }
                    continue;
                }
            }

            jarsToDecompile.add(dep.jarPath());
        }

        if (!jarsToDecompile.isEmpty()) {
            LOG.info("Decompiling {} dependency JARs without sources", jarsToDecompile.size());
            List<DecompileResult> results = decompiler.decompileJars(jarsToDecompile, decompiledDir);
            for (DecompileResult result : results) {
                if (result.hasOutput()) {
                    sourceDirs.add(result.outputDir());
                }
            }
        }

        LOG.info("Dependency resolution complete: {} source dirs from {} dependencies",
                sourceDirs.size(), deps.size());

        return new ResolveResult(sourceDirs, jarsToDecompile.size());
    }

    public MavenDownloadResult downloadSources(List<BuildTool.ResolvedDependency> deps, Path projectDir) {
        List<BuildTool.ResolvedDependency> missingSource = deps.stream()
                .filter(d -> !d.hasSourceJar() && d.jarPath() != null)
                .toList();

        if (missingSource.isEmpty()) {
            return new MavenDownloadResult(0, 0);
        }

        LOG.info("Attempting to download source JARs for {} dependencies", missingSource.size());

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "dependency:sources", "-q")
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("mvn dependency:sources timed out");
                return new MavenDownloadResult(0, missingSource.size());
            }

            int downloaded = 0;
            for (BuildTool.ResolvedDependency dep : missingSource) {
                Path sourceJar = dep.sourceJarPath();
                if (sourceJar != null && Files.exists(sourceJar)) {
                    downloaded++;
                }
            }

            LOG.info("Downloaded {} of {} source JARs", downloaded, missingSource.size());
            return new MavenDownloadResult(downloaded, missingSource.size() - downloaded);
        } catch (IOException | InterruptedException e) {
            LOG.warn("Failed to download source JARs: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new MavenDownloadResult(0, missingSource.size());
        }
    }

    private void extractSourceJar(Path sourceJar, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (FileSystem zipFs = FileSystems.newFileSystem(sourceJar)) {
            Path root = zipFs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.forEach(entry -> {
                    try {
                        String entryStr = entry.toString();
                        if (entryStr.endsWith(".java")) {
                            Path target = targetDir.resolve(root.relativize(entry).toString());
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

    public record ResolveResult(List<Path> sourceDirs, int decompiledCount) {}

    public record MavenDownloadResult(int downloaded, int failed) {}
}

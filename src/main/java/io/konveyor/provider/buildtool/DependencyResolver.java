package io.konveyor.provider.buildtool;

import io.konveyor.provider.decompiler.DecompileResult;
import io.konveyor.provider.decompiler.DecompilerService;
import io.konveyor.provider.decompiler.VineflowerDecompiler;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Obtains Java source for project dependencies so they can be indexed and analyzed.
 * For each dependency, tries in order: existing source JAR on disk, download via
 * Maven Resolver API, decompile binary JAR via {@link VineflowerDecompiler}.
 */
public class DependencyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyResolver.class);

    private static final RemoteRepository MAVEN_CENTRAL = new RemoteRepository.Builder(
            "central", "default", "https://repo.maven.apache.org/maven2/").build();

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

    /**
     * Downloads source JARs for dependencies that don't already have them,
     * using the Maven Resolver API in-process (no external mvn binary needed).
     */
    public DownloadResult downloadSourceJars(List<BuildTool.ResolvedDependency> deps) {
        List<BuildTool.ResolvedDependency> missingSource = deps.stream()
                .filter(d -> !d.hasSourceJar() && d.jarPath() != null && d.version() != null)
                .toList();

        if (missingSource.isEmpty()) {
            return new DownloadResult(0, 0);
        }

        LOG.info("Downloading source JARs for {} dependencies via Maven Resolver API", missingSource.size());

        RepositorySystem repoSystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession(repoSystem);
        List<RemoteRepository> remoteRepos = List.of(MAVEN_CENTRAL);

        int downloaded = 0;
        int failed = 0;

        for (BuildTool.ResolvedDependency dep : missingSource) {
            try {
                DefaultArtifact sourceArtifact = new DefaultArtifact(
                        dep.groupId(), dep.artifactId(), "sources", "jar", dep.version());
                ArtifactRequest request = new ArtifactRequest(sourceArtifact, remoteRepos, null);
                ArtifactResult result = repoSystem.resolveArtifact(session, request);
                if (result.isResolved()) {
                    downloaded++;
                    LOG.debug("Downloaded sources for {}:{}", dep.groupId(), dep.artifactId());
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                LOG.debug("No source JAR available for {}:{}:{}", dep.groupId(), dep.artifactId(), dep.version());
            }
        }

        LOG.info("Downloaded {}/{} source JARs ({} unavailable)", downloaded, missingSource.size(), failed);
        return new DownloadResult(downloaded, failed);
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

    @SuppressWarnings("deprecation")
    private RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    @SuppressWarnings("deprecation")
    private DefaultRepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepoPath = getLocalRepoPath();
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        session.setConfigProperty("aether.connector.connectTimeout", 10_000);
        session.setConfigProperty("aether.connector.requestTimeout", 30_000);
        return session;
    }

    private Path getLocalRepoPath() {
        String m2Repo = System.getProperty("maven.repo.local");
        if (m2Repo != null) {
            return Path.of(m2Repo);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    public record ResolveResult(List<Path> sourceDirs, int decompiledCount) {}

    public record DownloadResult(int downloaded, int failed) {}
}

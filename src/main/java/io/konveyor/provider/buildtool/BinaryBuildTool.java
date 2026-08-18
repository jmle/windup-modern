package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * {@link BuildTool} for standalone binary archives. Walks the project directory for
 * {@code .jar/.war/.ear} files and identifies each using a three-strategy pipeline:
 * SHA-1 index lookup, pom.properties extraction, file-name-based synthetic coordinates.
 */
public class BinaryBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(BinaryBuildTool.class);

    private final MavenShaIndex shaIndex;

    public BinaryBuildTool() {
        this(null);
    }

    public BinaryBuildTool(Path mavenIndexPath) {
        this.shaIndex = mavenIndexPath != null ? new MavenShaIndex(mavenIndexPath) : null;
    }

    @Override
    public Type getType() {
        return Type.BINARY;
    }

    @Override
    public List<ResolvedDependency> getDependencies(Path projectDir) {
        List<ResolvedDependency> deps = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.toString().toLowerCase();
                    return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
                })
                .forEach(jar -> deps.add(identifyJar(jar)));
        } catch (IOException e) {
            LOG.error("Failed to walk binary project directory: {}", projectDir, e);
        }

        LOG.info("Found {} binary artifacts in {}", deps.size(), projectDir);
        return deps;
    }

    public ResolvedDependency identifyJar(Path jarFile) {
        if (shaIndex != null) {
            Optional<MavenShaIndex.MavenCoordinates> coords = shaIndex.lookup(jarFile);
            if (coords.isPresent()) {
                MavenShaIndex.MavenCoordinates c = coords.get();
                LOG.debug("SHA-1 identified {} as {}:{}:{}", jarFile.getFileName(), c.groupId(), c.artifactId(), c.version());
                return new ResolvedDependency(c.groupId(), c.artifactId(), c.version(),
                        c.classifier(), "compile", jarFile, false, false, null);
            }
        }

        Optional<PomProperties> pomProps = extractPomProperties(jarFile);
        if (pomProps.isPresent()) {
            PomProperties p = pomProps.get();
            LOG.debug("pom.properties identified {} as {}:{}:{}", jarFile.getFileName(), p.groupId, p.artifactId, p.version);
            return new ResolvedDependency(p.groupId, p.artifactId, p.version,
                    null, "compile", jarFile, false, false, null);
        }

        String name = jarFile.getFileName().toString();
        String baseName = name.substring(0, name.lastIndexOf('.'));
        return new ResolvedDependency("io.konveyor.embededdep", baseName, "0.0.0-SNAPSHOT",
                null, "compile", jarFile, false, false, null);
    }

    static Optional<PomProperties> extractPomProperties(Path jarFile) {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        Properties props = new Properties();
                        props.load(is);
                        String groupId = props.getProperty("groupId");
                        String artifactId = props.getProperty("artifactId");
                        String version = props.getProperty("version");
                        if (groupId != null && artifactId != null) {
                            return Optional.of(new PomProperties(groupId, artifactId,
                                    version != null ? version : "0.0.0"));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to read pom.properties from {}: {}", jarFile, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Path getLocalRepoPath() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    record PomProperties(String groupId, String artifactId, String version) {}
}

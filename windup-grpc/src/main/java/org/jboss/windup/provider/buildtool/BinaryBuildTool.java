package org.jboss.windup.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link BuildTool} for standalone binary archives. Walks the project directory for
 * {@code .jar/.war/.ear} files and treats each as an embedded dependency with synthetic
 * coordinates ({@code io.konveyor.embededdep} group, {@code 0.0.0-SNAPSHOT} version).
 */
public class BinaryBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(BinaryBuildTool.class);

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
                .forEach(jar -> {
                    String name = jar.getFileName().toString();
                    String baseName = name.substring(0, name.lastIndexOf('.'));

                    deps.add(new ResolvedDependency(
                            "io.konveyor.embededdep",
                            baseName,
                            "0.0.0-SNAPSHOT",
                            null,
                            "compile",
                            jar,
                            false
                    ));
                });
        } catch (IOException e) {
            LOG.error("Failed to walk binary project directory: {}", projectDir, e);
        }

        LOG.info("Found {} binary artifacts in {}", deps.size(), projectDir);
        return deps;
    }

    @Override
    public Path getLocalRepoPath() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}

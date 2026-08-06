package org.jboss.windup.provider.buildtool;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over build tools (Maven, Gradle, bare binaries) for resolving project
 * dependencies. Implementations detect the tool from project files, execute dependency
 * resolution commands, and locate artifact JARs in the local cache.
 */
public interface BuildTool {

    enum Type { MAVEN, GRADLE, BINARY }

    Type getType();

    List<ResolvedDependency> getDependencies(Path projectDir);

    Path getLocalRepoPath();

    record ResolvedDependency(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String scope,
            Path jarPath,
            boolean hasSourceJar
    ) {
        public String name() {
            return groupId + "." + artifactId;
        }

        public Path sourceJarPath() {
            if (jarPath == null) return null;
            String name = jarPath.getFileName().toString();
            String sourceName = name.replace(".jar", "-sources.jar");
            return jarPath.getParent().resolve(sourceName);
        }
    }
}

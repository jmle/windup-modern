package io.konveyor.provider.buildtool;

import java.nio.file.Path;
import java.util.ArrayList;
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

    default List<DagEntry> getDependenciesDAG(Path projectDir) {
        return getDependencies(projectDir).stream()
                .map(dep -> new DagEntry(dep, List.of()))
                .toList();
    }

    Path getLocalRepoPath();

    record DagEntry(ResolvedDependency dep, List<DagEntry> children) {}

    static List<ResolvedDependency> flattenDag(List<DagEntry> dag) {
        List<ResolvedDependency> result = new ArrayList<>();
        for (DagEntry entry : dag) {
            flattenDagEntry(entry, result);
        }
        return result;
    }

    private static void flattenDagEntry(DagEntry entry, List<ResolvedDependency> result) {
        result.add(entry.dep());
        for (DagEntry child : entry.children()) {
            flattenDagEntry(child, result);
        }
    }

    record ResolvedDependency(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String scope,
            Path jarPath,
            boolean hasSourceJar,
            boolean indirect,
            String pomPath,
            ResolvedDependency baseDep
    ) {
        public ResolvedDependency(String groupId, String artifactId, String version,
                                  String classifier, String scope, Path jarPath,
                                  boolean hasSourceJar, boolean indirect, String pomPath) {
            this(groupId, artifactId, version, classifier, scope, jarPath,
                    hasSourceJar, indirect, pomPath, null);
        }

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

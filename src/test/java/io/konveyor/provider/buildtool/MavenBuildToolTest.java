package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MavenBuildTool}: JAR path resolution in the local Maven repository
 * layout, classifier handling, and missing artifact behavior.
 */
class MavenBuildToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveJarPathInLocalRepo() throws Exception {
        Path jarDir = tempDir.resolve("com/example/mylib/1.0.0");
        Files.createDirectories(jarDir);
        Files.writeString(jarDir.resolve("mylib-1.0.0.jar"), "fake-jar");

        Path result = MavenBuildTool.resolveJarPath(tempDir, "com.example", "mylib", "1.0.0", null, "jar");

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("mylib-1.0.0.jar");
    }

    @Test
    void shouldResolveJarPathWithClassifier() throws Exception {
        Path jarDir = tempDir.resolve("com/example/mylib/1.0.0");
        Files.createDirectories(jarDir);
        Files.writeString(jarDir.resolve("mylib-1.0.0-sources.jar"), "fake-source-jar");

        Path result = MavenBuildTool.resolveJarPath(tempDir, "com.example", "mylib", "1.0.0", "sources", "jar");

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("mylib-1.0.0-sources.jar");
    }

    @Test
    void shouldReturnNullForMissingJar() {
        Path result = MavenBuildTool.resolveJarPath(tempDir, "com.example", "missing", "1.0.0", null, "jar");
        assertThat(result).isNull();
    }

    @Test
    void shouldParseDepTreeLine() {
        MavenBuildTool tool = new MavenBuildTool();
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.MAVEN);
        assertThat(tool.getLocalRepoPath()).isNotNull();
    }

    @Test
    void flattenDagPreservesAllNodes() {
        var leaf1 = new BuildTool.DagEntry(
                new BuildTool.ResolvedDependency("g", "leaf1", "1.0", null, "compile", null, false, true, null),
                List.of());
        var leaf2 = new BuildTool.DagEntry(
                new BuildTool.ResolvedDependency("g", "leaf2", "2.0", null, "compile", null, false, true, null),
                List.of());
        var mid = new BuildTool.DagEntry(
                new BuildTool.ResolvedDependency("g", "mid", "1.0", null, "compile", null, false, true, null),
                List.of(leaf2));
        var root = new BuildTool.DagEntry(
                new BuildTool.ResolvedDependency("g", "root", "1.0", null, "compile", null, false, false, null),
                List.of(leaf1, mid));

        List<BuildTool.ResolvedDependency> flat = BuildTool.flattenDag(List.of(root));

        assertThat(flat).hasSize(4);
        assertThat(flat.stream().map(BuildTool.ResolvedDependency::artifactId).toList())
                .containsExactly("root", "leaf1", "mid", "leaf2");
    }

    @Test
    void flattenDagEmptyReturnsEmpty() {
        assertThat(BuildTool.flattenDag(List.of())).isEmpty();
    }
}

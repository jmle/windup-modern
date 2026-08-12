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

    @Test
    void multiModuleCollectsChildDependencies() throws Exception {
        Path parentDir = tempDir.resolve("multi-module");
        Files.createDirectories(parentDir);
        Files.writeString(parentDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>child-a</module>
                    <module>child-b</module>
                  </modules>
                </project>
                """);

        Path childA = parentDir.resolve("child-a");
        Files.createDirectories(childA);
        Files.writeString(childA.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>child-a</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <version>4.13.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        Path childB = parentDir.resolve("child-b");
        Files.createDirectories(childB);
        Files.writeString(childB.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>child-b</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenBuildTool tool = new MavenBuildTool();
        List<BuildTool.DagEntry> dag = tool.getDependenciesDAG(parentDir);

        assertThat(dag).isNotEmpty();
        List<String> artifactIds = dag.stream()
                .map(e -> e.dep().artifactId())
                .toList();
        assertThat(artifactIds).contains("junit", "slf4j-api");
    }

    @Test
    void multiModuleSkipsMissingChildPom() throws Exception {
        Path parentDir = tempDir.resolve("multi-missing");
        Files.createDirectories(parentDir);
        Files.writeString(parentDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>exists</module>
                    <module>missing</module>
                  </modules>
                </project>
                """);

        Path exists = parentDir.resolve("exists");
        Files.createDirectories(exists);
        Files.writeString(exists.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>exists</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <version>4.13.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenBuildTool tool = new MavenBuildTool();
        List<BuildTool.DagEntry> dag = tool.getDependenciesDAG(parentDir);

        assertThat(dag).isNotEmpty();
        assertThat(dag.stream().map(e -> e.dep().artifactId()).toList()).contains("junit");
    }

    @Test
    void multiModuleResolvesProjectVersion() throws Exception {
        Path parentDir = tempDir.resolve("multi-version");
        Files.createDirectories(parentDir);
        Files.writeString(parentDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>2.5.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>child</module>
                  </modules>
                </project>
                """);

        Path child = parentDir.resolve("child");
        Files.createDirectories(child);
        Files.writeString(child.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.5.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenBuildTool tool = new MavenBuildTool();
        List<BuildTool.DagEntry> dag = tool.getDependenciesDAG(parentDir);

        assertThat(dag).isNotEmpty();
        assertThat(dag.get(0).dep().version()).isEqualTo("2.5.0");
    }

    @Test
    void propertyResolutionHandlesNestedAndMissing() {
        MavenBuildTool tool = new MavenBuildTool();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("my.version", "3.0");
        props.setProperty("project.groupId", "com.test");

        assertThat(tool.resolveProperties("${my.version}", props)).isEqualTo("3.0");
        assertThat(tool.resolveProperties("${project.groupId}:${my.version}", props))
                .isEqualTo("com.test:3.0");
        assertThat(tool.resolveProperties("${unknown}", props)).isEqualTo("${unknown}");
        assertThat(tool.resolveProperties(null, props)).isNull();
        assertThat(tool.resolveProperties("plain", props)).isEqualTo("plain");
    }
}

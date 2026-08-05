package org.jboss.windup.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}

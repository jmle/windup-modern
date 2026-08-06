package org.jboss.windup.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BuildToolDetector}: verifies detection of Maven, Gradle,
 * Kotlin Gradle, priority when multiple build files coexist, and the default
 * fallback to Maven for empty directories.
 */
class BuildToolDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        BuildTool tool = BuildToolDetector.detect(tempDir);
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.MAVEN);
    }

    @Test
    void shouldDetectGradle() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        BuildTool tool = BuildToolDetector.detect(tempDir);
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    @Test
    void shouldDetectGradleKotlin() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins {}");
        BuildTool tool = BuildToolDetector.detect(tempDir);
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    @Test
    void shouldPreferGradleOverMaven() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        BuildTool tool = BuildToolDetector.detect(tempDir);
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    @Test
    void shouldDefaultToMaven() throws Exception {
        BuildTool tool = BuildToolDetector.detect(tempDir);
        assertThat(tool.getType()).isEqualTo(BuildTool.Type.MAVEN);
    }
}

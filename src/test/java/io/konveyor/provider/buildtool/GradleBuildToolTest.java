package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradleBuildToolTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveGradleJarPathFindsJar() throws Exception {
        Path jarDir = tempDir.resolve("com.example/mylib/1.0/abc123");
        Files.createDirectories(jarDir);
        Files.writeString(jarDir.resolve("mylib-1.0.jar"), "fake-jar");

        Path result = GradleBuildTool.resolveGradleJarPath(tempDir, "com.example", "mylib", "1.0");
        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("mylib-1.0.jar");
    }

    @Test
    void resolveGradleJarPathReturnsNullWhenMissing() {
        Path result = GradleBuildTool.resolveGradleJarPath(tempDir, "com.example", "missing", "1.0");
        assertThat(result).isNull();
    }

    @Test
    void hasSourceJarDetectsSourcesJar() throws Exception {
        Path jarDir = tempDir.resolve("com.example/mylib/1.0/abc123");
        Files.createDirectories(jarDir);
        Files.writeString(jarDir.resolve("mylib-1.0-sources.jar"), "fake-sources");

        assertThat(GradleBuildTool.hasSourceJar(tempDir, "com.example", "mylib", "1.0")).isTrue();
    }

    @Test
    void hasSourceJarReturnsFalseWhenMissing() {
        assertThat(GradleBuildTool.hasSourceJar(tempDir, "com.example", "mylib", "1.0")).isFalse();
    }

    @Test
    void parsesSubprojectOutput() {
        GradleBuildTool tool = new GradleBuildTool();

        List<String> output = List.of(
                "",
                "------------------------------------------------------------",
                "Root project 'my-app'",
                "------------------------------------------------------------",
                "",
                "Root project 'my-app'",
                "+--- Project ':core'",
                "+--- Project ':web'",
                "\\--- Project ':api'",
                "",
                "To see a list of the tasks, run gradlew tasks"
        );

        List<String> subprojects = parseSubprojects(tool, output);
        assertThat(subprojects).containsExactly(":core", ":web", ":api");
    }

    @Test
    void parsesNoSubprojects() {
        GradleBuildTool tool = new GradleBuildTool();

        List<String> output = List.of(
                "Root project 'my-app'",
                "No sub-projects"
        );

        List<String> subprojects = parseSubprojects(tool, output);
        assertThat(subprojects).isEmpty();
    }

    @Test
    void detectsGradleVersionFromWrapperProperties() throws Exception {
        Path gradleDir = tempDir.resolve("gradle/wrapper");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n");

        GradleBuildTool tool = new GradleBuildTool();
        String version = tool.getGradleVersion(tempDir);
        assertThat(version).isEqualTo("8.5");
    }

    @Test
    void detectsGradle9FromWrapperProperties() throws Exception {
        Path gradleDir = tempDir.resolve("gradle/wrapper");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0.1-bin.zip\n");

        GradleBuildTool tool = new GradleBuildTool();
        assertThat(tool.isGradle9OrLater(tempDir)).isTrue();
    }

    @Test
    void detectsGradle8AsNotGradle9() throws Exception {
        Path gradleDir = tempDir.resolve("gradle/wrapper");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n");

        GradleBuildTool tool = new GradleBuildTool();
        assertThat(tool.isGradle9OrLater(tempDir)).isFalse();
    }

    @Test
    void findBuildFilePrefersBuildGradle() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "// groovy");

        GradleBuildTool tool = new GradleBuildTool();
        assertThat(tool.findBuildFile(tempDir).getFileName().toString()).isEqualTo("build.gradle");
    }

    @Test
    void findBuildFileFallsBackToKts() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// kotlin");

        GradleBuildTool tool = new GradleBuildTool();
        assertThat(tool.findBuildFile(tempDir).getFileName().toString()).isEqualTo("build.gradle.kts");
    }

    @Test
    void typeIsGradle() {
        assertThat(new GradleBuildTool().getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    private List<String> parseSubprojects(GradleBuildTool tool, List<String> output) {
        List<String> subprojects = new java.util.ArrayList<>();
        boolean inProjects = false;
        var pattern = java.util.regex.Pattern.compile(".*- Project '(.*)'");

        for (String line : output) {
            if (line.contains("No sub-projects")) return List.of();
            if (line.contains("Root project")) {
                inProjects = true;
                continue;
            }
            if (line.contains("To see a list of")) break;

            if (inProjects) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    subprojects.add(m.group(1));
                }
            }
        }
        return subprojects;
    }
}

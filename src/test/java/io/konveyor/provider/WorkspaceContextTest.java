package io.konveyor.provider;

import io.konveyor.provider.buildtool.BuildTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link WorkspaceContext#alternativeBuildTool} — the build-tool fallback used when the
 * detected tool resolves 0 dependencies (e.g. a Gradle toolchain/env failure on a project that also ships
 * a pom.xml). See testing/BASELINE_COMPARISONS.md (petclinic).
 */
class WorkspaceContextTest {

    @TempDir
    Path dir;

    private void touch(String name) throws Exception {
        Files.writeString(dir.resolve(name), "");
    }

    @Test
    void gradleProjectWithPomFallsBackToMaven() throws Exception {
        touch("build.gradle");
        touch("pom.xml");
        BuildTool alt = WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.GRADLE);
        assertThat(alt).isNotNull();
        assertThat(alt.getType()).isEqualTo(BuildTool.Type.MAVEN);
    }

    @Test
    void mavenProjectWithGradleFallsBackToGradle() throws Exception {
        touch("pom.xml");
        touch("build.gradle");
        BuildTool alt = WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.MAVEN);
        assertThat(alt).isNotNull();
        assertThat(alt.getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    @Test
    void mavenProjectWithKotlinDslFallsBackToGradle() throws Exception {
        touch("pom.xml");
        touch("build.gradle.kts");
        BuildTool alt = WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.MAVEN);
        assertThat(alt).isNotNull();
        assertThat(alt.getType()).isEqualTo(BuildTool.Type.GRADLE);
    }

    @Test
    void gradleOnlyProjectHasNoAlternative() throws Exception {
        touch("build.gradle");
        assertThat(WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.GRADLE)).isNull();
    }

    @Test
    void mavenOnlyProjectHasNoAlternative() throws Exception {
        touch("pom.xml");
        assertThat(WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.MAVEN)).isNull();
    }

    @Test
    void binaryInputHasNoAlternative() throws Exception {
        touch("pom.xml");
        touch("build.gradle");
        // binary analysis never falls back to a source build tool
        assertThat(WorkspaceContext.alternativeBuildTool(dir, BuildTool.Type.BINARY)).isNull();
    }

    // ── mavenIndexFileFrom (D3: kantra passes the install DIR, not the file) ──────────────────────

    @Test
    void mavenIndexResolvesFileInsideDirectory() throws Exception {
        Files.writeString(dir.resolve("maven-index.txt"), "x");
        assertThat(WorkspaceContext.mavenIndexFileFrom(dir.toString()))
                .isEqualTo(dir.resolve("maven-index.txt"));
    }

    @Test
    void mavenIndexDirectoryWithoutFileIsNull() {
        assertThat(WorkspaceContext.mavenIndexFileFrom(dir.toString())).isNull();
    }

    @Test
    void mavenIndexAcceptsDirectFilePath() throws Exception {
        Path f = dir.resolve("custom-index.txt");
        Files.writeString(f, "x");
        assertThat(WorkspaceContext.mavenIndexFileFrom(f.toString())).isEqualTo(f);
    }

    @Test
    void mavenIndexNullOrMissingIsNull() {
        assertThat(WorkspaceContext.mavenIndexFileFrom(null)).isNull();
        assertThat(WorkspaceContext.mavenIndexFileFrom("")).isNull();
        assertThat(WorkspaceContext.mavenIndexFileFrom(dir.resolve("nope.txt").toString())).isNull();
    }
}

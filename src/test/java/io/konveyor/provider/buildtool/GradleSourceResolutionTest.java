package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Gradle dependency-SOURCE download (MTA-8217), the feature the Go provider had. Runs the
 * version-aware {@code konveyorDownloadSources} task against a real Gradle project and verifies the
 * {@code -sources.jar}s are fetched and extracted into indexable source trees.
 *
 * <p>Gated on Gradle being on {@code PATH} (needs network); skips cleanly otherwise.</p>
 */
@EnabledIf("gradleAvailable")
class GradleSourceResolutionTest {

    @TempDir
    Path dir;

    @BeforeEach
    void writeProject() throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'gradle-sources'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.13'
                }
                """);
    }

    @Test
    void downloadsAndExtractsDependencySources() {
        GradleBuildTool gradle = new GradleBuildTool();
        List<BuildTool.ResolvedDependency> deps = gradle.getDependencies(dir);
        assertThat(deps).anyMatch(d -> d.artifactId().equals("slf4j-api"));

        // version-aware source download task actually fetches the -sources.jar
        gradle.downloadSources(dir);
        Path downloads = dir.resolve("build").resolve("downloads");
        assertThat(downloads.resolve("slf4j-api-2.0.13-sources.jar"))
                .as("Gradle source-download task must fetch the -sources.jar").exists();

        // and we extract it into an indexable source tree (real source, not decompiled)
        DependencyResolver.ResolveResult result = new DependencyResolver()
                .resolveFromDownloadedSources(deps, downloads, dir.resolve("work"));
        assertThat(result.sourceDirs()).isNotEmpty();
        boolean hasRealSource = result.sourceDirs().stream()
                .anyMatch(sd -> Files.exists(sd.resolve("org/slf4j/Logger.java")));
        assertThat(hasRealSource).as("extracted real slf4j source (org/slf4j/Logger.java)").isTrue();
    }

    static boolean gradleAvailable() {
        try {
            Process p = new ProcessBuilder("gradle", "--version").redirectErrorStream(true).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

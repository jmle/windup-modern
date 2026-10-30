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
 * Gradle <b>Kotlin-DSL</b> ({@code build.gradle.kts}) coverage (MTA-8159 compatibility matrix).
 *
 * <p>Regression guard for a real bug: the task scripts used to be appended to the project's build
 * file, which is a Kotlin compile error on a {@code .kts} project ({@code Expecting an element}).
 * They are now injected as Groovy {@code --init-script}s, which apply regardless of the build DSL.
 * This test proves dependency resolution AND dependency-source download both work for a Kotlin-DSL
 * project end-to-end.</p>
 *
 * <p>Gated on Gradle being on {@code PATH} (needs network); skips cleanly otherwise.</p>
 */
@EnabledIf("gradleAvailable")
class GradleKotlinDslTest {

    @TempDir
    Path dir;

    @BeforeEach
    void writeKotlinDslProject() throws Exception {
        Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"gradle-kts\"\n");
        Files.writeString(dir.resolve("build.gradle.kts"), """
                plugins { `java` }
                repositories { mavenCentral() }
                dependencies {
                    implementation("org.slf4j:slf4j-api:2.0.13")
                }
                """);
    }

    @Test
    void resolvesDependenciesForKotlinDslProject() {
        GradleBuildTool gradle = new GradleBuildTool();
        List<BuildTool.ResolvedDependency> deps = gradle.getDependencies(dir);
        assertThat(deps)
                .as("Kotlin-DSL project dependencies must resolve")
                .anyMatch(d -> d.artifactId().equals("slf4j-api") && d.version().equals("2.0.13"));
    }

    @Test
    void downloadsAndExtractsSourcesForKotlinDslProject() {
        GradleBuildTool gradle = new GradleBuildTool();
        List<BuildTool.ResolvedDependency> deps = gradle.getDependencies(dir);
        assertThat(deps).anyMatch(d -> d.artifactId().equals("slf4j-api"));

        // init-script source download must work despite the Kotlin build file
        gradle.downloadSources(dir);
        Path downloads = dir.resolve("build").resolve("downloads");
        assertThat(downloads.resolve("slf4j-api-2.0.13-sources.jar"))
                .as("Gradle source-download task must fetch the -sources.jar for a .kts project")
                .exists();

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

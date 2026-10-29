package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Gradle dependency-resolution parity (MTA-8158... MTA-8155): resolve a real Gradle project
 * through {@link GradleBuildTool} and check it against the versions / direct-vs-transitive structure that
 * {@code gradle dependencies} reports. Pinned versions keep the assertions deterministic.
 *
 * <p>{@link GradleBuildTool} shells out to Gradle, so this is gated on Gradle being on {@code PATH}
 * (and needs network for Maven Central); it skips cleanly otherwise.</p>
 */
@EnabledIf("gradleAvailable")
class GradleResolutionTest {

    @TempDir
    Path dir;

    @BeforeEach
    void writeProject() throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'gradle-parity'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.13'
                    implementation 'com.google.guava:guava:32.1.3-jre'
                }
                """);
    }

    @Test
    void resolvesDirectAndTransitiveWithCorrectVersions() {
        List<BuildTool.ResolvedDependency> deps = new GradleBuildTool().getDependencies(dir);

        // direct deps: exact declared versions
        assertThat(dep(deps, "slf4j-api")).isPresent();
        assertThat(dep(deps, "slf4j-api").get().version()).isEqualTo("2.0.13");
        assertThat(dep(deps, "slf4j-api").get().indirect()).isFalse();

        assertThat(dep(deps, "guava")).isPresent();
        assertThat(dep(deps, "guava").get().version()).isEqualTo("32.1.3-jre");
        assertThat(dep(deps, "guava").get().indirect()).isFalse();

        // a transitive of guava is present and flagged indirect
        assertThat(dep(deps, "failureaccess")).isPresent();
        assertThat(dep(deps, "failureaccess").get().indirect()).isTrue();
    }

    private static Optional<BuildTool.ResolvedDependency> dep(
            List<BuildTool.ResolvedDependency> deps, String artifactId) {
        return deps.stream().filter(d -> artifactId.equals(d.artifactId())).findFirst();
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

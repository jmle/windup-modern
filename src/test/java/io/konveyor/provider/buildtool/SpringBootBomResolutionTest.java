package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Online dependency-parity test for recursive BOM-import resolution (TEST_PLAN.md L3).
 *
 * <p>Resolves a project that imports the real {@code spring-boot-dependencies:3.3.4} BOM and declares a
 * <em>version-less</em> {@code spring-data-jpa}. That artifact is NOT managed directly by
 * spring-boot-dependencies — its version comes from the nested {@code spring-data-bom} import — so it
 * exercises {@link MavenBuildTool}'s recursive, parent-aware BOM resolution. Expected versions are
 * pinned (verified against {@code mvn dependency:tree}) so the assertions are deterministic.
 *
 * <p>Gated on Maven Central reachability, so it skips cleanly offline.
 */
@EnabledIf("mavenCentralReachable")
class SpringBootBomResolutionTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void writePom() throws Exception {
        Files.writeString(projectDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>io.konveyor.test</groupId>
                    <artifactId>bom-parity</artifactId>
                    <version>1.0</version>
                    <packaging>jar</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.3.4</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <!-- version-less: resolvable only via the nested spring-data-bom import -->
                        <dependency>
                            <groupId>org.springframework.data</groupId>
                            <artifactId>spring-data-jpa</artifactId>
                        </dependency>
                        <!-- brings micrometer-core transitively -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }

    @Test
    void resolvesNestedBomManagedVersionsMatchingMavenOracle() {
        List<BuildTool.ResolvedDependency> deps = new MavenBuildTool().getDependencies(projectDir);

        // spring-data-jpa: version managed via the NESTED spring-data-bom import (old code dropped it)
        Optional<BuildTool.ResolvedDependency> jpa = find(deps, "org.springframework.data", "spring-data-jpa");
        assertThat(jpa).as("spring-data-jpa must resolve via nested spring-data-bom import").isPresent();
        assertThat(jpa.get().version()).isEqualTo("3.3.4");

        // transitive of spring-data-jpa
        Optional<BuildTool.ResolvedDependency> commons = find(deps, "org.springframework.data", "spring-data-commons");
        assertThat(commons).isPresent();
        assertThat(commons.get().version()).isEqualTo("3.3.4");

        // transitive via spring-boot-starter-actuator (the "lost transitive" symptom)
        Optional<BuildTool.ResolvedDependency> micrometer = find(deps, "io.micrometer", "micrometer-core");
        assertThat(micrometer).as("micrometer-core must appear transitively").isPresent();
        assertThat(micrometer.get().version()).isEqualTo("1.13.4");
    }

    private static Optional<BuildTool.ResolvedDependency> find(
            List<BuildTool.ResolvedDependency> deps, String groupId, String artifactId) {
        return deps.stream()
                .filter(d -> groupId.equals(d.groupId()) && artifactId.equals(d.artifactId()))
                .findFirst();
    }

    static boolean mavenCentralReachable() {
        try {
            URI uri = URI.create("https://repo.maven.apache.org/maven2/org/springframework/boot/"
                    + "spring-boot-dependencies/3.3.4/spring-boot-dependencies-3.3.4.pom");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}

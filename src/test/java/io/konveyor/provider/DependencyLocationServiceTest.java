package io.konveyor.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyLocationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void findInPom_locatesGroupIdLine() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>5.3.20</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "org.springframework", "spring-core");
        assertThat(line).isEqualTo(4);
    }

    @Test
    void findInPom_locatesSecondDependency() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "org.slf4j", "slf4j-api");
        assertThat(line).isEqualTo(9);
    }

    @Test
    void findInPom_returnsZeroWhenNotFound() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "com.nonexistent", "missing");
        assertThat(line).isEqualTo(0);
    }

    @Test
    void findInGradle_locatesDependency() throws IOException {
        Path gradle = tempDir.resolve("build.gradle");
        Files.writeString(gradle, """
                plugins {
                    id 'java'
                }
                dependencies {
                    implementation 'org.springframework:spring-core:5.3.20'
                    implementation 'org.slf4j:slf4j-api:2.0.0'
                }
                """);

        int line = DependencyLocationService.findDependencyLine(gradle, "org.slf4j", "slf4j-api");
        assertThat(line).isEqualTo(6);
    }

    @Test
    void findInGradle_returnsZeroWhenNotFound() throws IOException {
        Path gradle = tempDir.resolve("build.gradle");
        Files.writeString(gradle, """
                dependencies {
                    implementation 'org.springframework:spring-core:5.3.20'
                }
                """);

        int line = DependencyLocationService.findDependencyLine(gradle, "com.nonexistent", "missing");
        assertThat(line).isEqualTo(0);
    }
}

package org.jboss.windup.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MavenShaIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLookupBySha1() throws Exception {
        Path jarFile = tempDir.resolve("test.jar");
        Files.writeString(jarFile, "fake jar content for sha1 test");

        String sha1 = MavenShaIndex.computeSha1(jarFile);

        Path indexFile = tempDir.resolve("maven-index.txt");
        Files.writeString(indexFile, """
                0000000000000000000000000000000000000000 com.example:first:jar:1.0.0
                %s com.example:test-lib:jar:2.1.0
                ffffffffffffffffffffffffffffffffffffffff com.example:last:jar:3.0.0
                """.formatted(sha1));

        MavenShaIndex index = new MavenShaIndex(indexFile);
        Optional<MavenShaIndex.MavenCoordinates> result = index.lookup(jarFile);

        assertThat(result).isPresent();
        assertThat(result.get().groupId()).isEqualTo("com.example");
        assertThat(result.get().artifactId()).isEqualTo("test-lib");
        assertThat(result.get().version()).isEqualTo("2.1.0");
    }

    @Test
    void shouldReturnEmptyForUnknownJar() throws Exception {
        Path jarFile = tempDir.resolve("unknown.jar");
        Files.writeString(jarFile, "unknown content");

        Path indexFile = tempDir.resolve("maven-index.txt");
        Files.writeString(indexFile, """
                0000000000000000000000000000000000000000 com.example:known:jar:1.0.0
                """);

        MavenShaIndex index = new MavenShaIndex(indexFile);
        Optional<MavenShaIndex.MavenCoordinates> result = index.lookup(jarFile);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleDirectoryPath() throws Exception {
        Path dir = tempDir.resolve("index-dir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("maven-index.txt"), """
                0000000000000000000000000000000000000000 com.example:test:jar:1.0
                """);

        MavenShaIndex index = new MavenShaIndex(dir);
        assertThat(index).isNotNull();
    }

    @Test
    void shouldParseCoordinates() {
        Optional<MavenShaIndex.MavenCoordinates> result =
                MavenShaIndex.parseCoordinates("org.springframework:spring-core:jar:5.3.20");

        assertThat(result).isPresent();
        assertThat(result.get().groupId()).isEqualTo("org.springframework");
        assertThat(result.get().artifactId()).isEqualTo("spring-core");
        assertThat(result.get().version()).isEqualTo("5.3.20");
        assertThat(result.get().packaging()).isEqualTo("jar");
    }

    @Test
    void shouldComputeSha1() throws Exception {
        Path file = tempDir.resolve("test.bin");
        Files.writeString(file, "hello");

        String sha1 = MavenShaIndex.computeSha1(file);
        assertThat(sha1).hasSize(40);
        assertThat(sha1).isEqualTo("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d");
    }
}

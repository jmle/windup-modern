package io.konveyor.provider.buildtool;

import io.konveyor.provider.buildtool.MavenArtifactDownloader.MvnCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for the {@code mvn://} path (MTA-8159 compatibility matrix): URI parsing and an offline,
 * local-repo end-to-end download. kantra/users can point an analysis at a published artifact via
 * {@code mvn://groupId:artifactId:version[:classifier][@outputDir]}.
 */
class MavenArtifactDownloaderTest {

    @Test
    void isMvnUriDetectsPrefix() {
        assertThat(MavenArtifactDownloader.isMvnUri("mvn://g:a:1.0")).isTrue();
        assertThat(MavenArtifactDownloader.isMvnUri("/path/to/app")).isFalse();
        assertThat(MavenArtifactDownloader.isMvnUri(null)).isFalse();
    }

    @Test
    void parsesGroupArtifactVersion() {
        MvnCoordinates c = MavenArtifactDownloader.parseUri("mvn://com.example:foo:1.2.3");
        assertThat(c.groupId()).isEqualTo("com.example");
        assertThat(c.artifactId()).isEqualTo("foo");
        assertThat(c.version()).isEqualTo("1.2.3");
        assertThat(c.classifier()).isNull();
        assertThat(c.outputDir()).isNull();
    }

    @Test
    void parsesClassifierAndOutputDir() {
        MvnCoordinates c = MavenArtifactDownloader.parseUri("mvn://com.example:foo:1.0:sources@/tmp/out");
        assertThat(c.classifier()).isEqualTo("sources");
        assertThat(c.outputDir()).isEqualTo(Path.of("/tmp/out"));
        assertThat(c.version()).isEqualTo("1.0");
    }

    @Test
    void rejectsTooFewCoordinates() {
        assertThatThrownBy(() -> MavenArtifactDownloader.parseUri("mvn://foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId:artifactId:version");
    }

    @Test
    void downloadsFromLocalRepoOffline(@TempDir Path tmp) throws Exception {
        // lay out com.example:foo:1.0 in a temp local repo (standard Maven layout)
        Path repo = tmp.resolve("m2");
        Path artDir = repo.resolve("com/example/foo/1.0");
        Files.createDirectories(artDir);
        Files.writeString(artDir.resolve("foo-1.0.jar"), "dummy-jar-bytes");
        Files.writeString(artDir.resolve("foo-1.0.pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                        + "<artifactId>foo</artifactId><version>1.0</version></project>");

        String savedRepo = System.getProperty("maven.repo.local");
        String savedOffline = System.getProperty("maven.offline");
        try {
            System.setProperty("maven.repo.local", repo.toString());
            System.setProperty("maven.offline", "true");  // resolve purely from the local repo, no network

            Path out = new MavenArtifactDownloader().download("mvn://com.example:foo:1.0", tmp.resolve("work"));

            assertThat(out).exists();
            assertThat(out.getFileName().toString()).isEqualTo("foo-1.0.jar");
            assertThat(Files.readString(out)).isEqualTo("dummy-jar-bytes");
        } finally {
            restore("maven.repo.local", savedRepo);
            restore("maven.offline", savedOffline);
        }
    }

    private static void restore(String key, String val) {
        if (val == null) System.clearProperty(key);
        else System.setProperty(key, val);
    }
}

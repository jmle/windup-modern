package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryBuildToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFallBackToSyntheticCoordinates() throws Exception {
        Path jar = tempDir.resolve("mylib.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("Dummy.class"));
            jos.write(new byte[]{0});
            jos.closeEntry();
        }

        BinaryBuildTool tool = new BinaryBuildTool();
        BuildTool.ResolvedDependency dep = tool.identifyJar(jar);

        assertThat(dep.groupId()).isEqualTo("io.konveyor.embededdep");
        assertThat(dep.artifactId()).isEqualTo("mylib");
        assertThat(dep.version()).isEqualTo("0.0.0-SNAPSHOT");
    }

    @Test
    void shouldIdentifyFromPomProperties() throws Exception {
        Path jar = tempDir.resolve("guava-31.1.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/com.google.guava/guava/pom.properties"));
            String props = "groupId=com.google.guava\nartifactId=guava\nversion=31.1-jre\n";
            jos.write(props.getBytes());
            jos.closeEntry();
        }

        BinaryBuildTool tool = new BinaryBuildTool();
        BuildTool.ResolvedDependency dep = tool.identifyJar(jar);

        assertThat(dep.groupId()).isEqualTo("com.google.guava");
        assertThat(dep.artifactId()).isEqualTo("guava");
        assertThat(dep.version()).isEqualTo("31.1-jre");
    }

    @Test
    void shouldIdentifyFromShaIndex() throws Exception {
        Path jar = tempDir.resolve("unknown.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("Dummy.class"));
            jos.write("test-content".getBytes());
            jos.closeEntry();
        }

        String sha1 = MavenShaIndex.computeSha1(jar);
        Path indexFile = tempDir.resolve("maven-index.txt");
        Files.writeString(indexFile, buildIndexWith(sha1, "org.example:mylib:jar:2.0.0"));

        BinaryBuildTool tool = new BinaryBuildTool(indexFile);
        BuildTool.ResolvedDependency dep = tool.identifyJar(jar);

        assertThat(dep.groupId()).isEqualTo("org.example");
        assertThat(dep.artifactId()).isEqualTo("mylib");
        assertThat(dep.version()).isEqualTo("2.0.0");
    }

    @Test
    void shaIndexTakesPriorityOverPomProperties() throws Exception {
        Path jar = tempDir.resolve("lib.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/old.group/old-art/pom.properties"));
            String props = "groupId=old.group\nartifactId=old-art\nversion=1.0\n";
            jos.write(props.getBytes());
            jos.closeEntry();
        }

        String sha1 = MavenShaIndex.computeSha1(jar);
        Path indexFile = tempDir.resolve("maven-index.txt");
        Files.writeString(indexFile, buildIndexWith(sha1, "correct.group:correct-art:jar:3.0"));

        BinaryBuildTool tool = new BinaryBuildTool(indexFile);
        BuildTool.ResolvedDependency dep = tool.identifyJar(jar);

        assertThat(dep.groupId()).isEqualTo("correct.group");
        assertThat(dep.artifactId()).isEqualTo("correct-art");
    }

    private String buildIndexWith(String sha1, String coords) {
        // Binary search needs multiple lines to work correctly
        return "0000000000000000000000000000000000000000 dummy:before:jar:0.0\n"
                + sha1 + " " + coords + "\n"
                + "ffffffffffffffffffffffffffffffffffffffff dummy:after:jar:9.9\n";
    }

    @Test
    void extractPomPropertiesReturnsEmptyForNoProps() throws Exception {
        Path jar = tempDir.resolve("empty.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("Dummy.class"));
            jos.write(new byte[]{0});
            jos.closeEntry();
        }

        Optional<BinaryBuildTool.PomProperties> result = BinaryBuildTool.extractPomProperties(jar);
        assertThat(result).isEmpty();
    }

    @Test
    void getDependenciesWalksDirectoryAndIdentifies() throws Exception {
        Path projectDir = tempDir.resolve("binaries");
        Files.createDirectories(projectDir);

        Path jar1 = projectDir.resolve("a.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar1))) {
            jos.putNextEntry(new JarEntry("META-INF/maven/com.a/a/pom.properties"));
            jos.write("groupId=com.a\nartifactId=a\nversion=1.0\n".getBytes());
            jos.closeEntry();
        }

        Path jar2 = projectDir.resolve("b.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar2))) {
            jos.putNextEntry(new JarEntry("Dummy.class"));
            jos.write(new byte[]{0});
            jos.closeEntry();
        }

        BinaryBuildTool tool = new BinaryBuildTool();
        List<BuildTool.ResolvedDependency> deps = tool.getDependencies(projectDir);

        assertThat(deps).hasSize(2);
        assertThat(deps.stream().map(BuildTool.ResolvedDependency::groupId).toList())
                .containsExactlyInAnyOrder("com.a", "io.konveyor.embededdep");
    }
}

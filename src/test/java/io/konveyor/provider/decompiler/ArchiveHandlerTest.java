package io.konveyor.provider.decompiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ArchiveHandler}: archive explosion, JAR handling (decompile to source),
 * and WAR handling (decompile WEB-INF/classes, extract WEB-INF/lib dependencies).
 * Builds real compiled JARs and WARs from source at test time.
 */
class ArchiveHandlerTest {

    @TempDir
    Path tempDir;

    ArchiveHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ArchiveHandler(new VineflowerDecompiler(1));
    }

    @Test
    void shouldExplodeArchive() throws Exception {
        Path jar = createSimpleJar("test.jar", "com/example/Foo.class");
        Path explodedDir = tempDir.resolve("exploded");
        ArchiveHandler.explode(jar, explodedDir);

        assertThat(explodedDir.resolve("com/example/Foo.class")).exists();
    }

    @Test
    void shouldHandleJar() throws Exception {
        Path jar = createCompiledJar("test.jar", "Foo");
        Path workDir = tempDir.resolve("work");

        ArchiveHandler.ArchiveResult result = handler.handleArchive(jar, workDir);

        assertThat(result.archivePath()).isEqualTo(jar);
        assertThat(result.sourceDirs()).isNotEmpty();
        assertThat(result.dependencyJars()).isEmpty();
    }

    @Test
    void shouldHandleWar() throws Exception {
        Path war = createTestWar();
        Path workDir = tempDir.resolve("work");

        ArchiveHandler.ArchiveResult result = handler.handleArchive(war, workDir);

        assertThat(result.archivePath()).isEqualTo(war);
        assertThat(result.dependencyJars()).isNotEmpty();
    }

    private Path createSimpleJar(String name, String... entries) throws IOException {
        Path jar = tempDir.resolve(name);
        try (FileSystem zipFs = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            for (String entry : entries) {
                Path entryPath = zipFs.getPath(entry);
                if (entryPath.getParent() != null) {
                    Files.createDirectories(entryPath.getParent());
                }
                Files.write(entryPath, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            }
        }
        return jar;
    }

    private Path createCompiledJar(String jarName, String className) throws Exception {
        Path sourceDir = tempDir.resolve("src-" + className);
        Path packageDir = sourceDir.resolve("com/example");
        Files.createDirectories(packageDir);

        Files.writeString(packageDir.resolve(className + ".java"), """
                package com.example;
                public class %s {
                    public String getValue() { return "test"; }
                }
                """.formatted(className));

        Path classesDir = tempDir.resolve("classes-" + className);
        Files.createDirectories(classesDir);

        new ProcessBuilder("javac", "-d", classesDir.toString(),
                packageDir.resolve(className + ".java").toString())
                .redirectErrorStream(true).start().waitFor();

        Path jarPath = tempDir.resolve(jarName);
        new ProcessBuilder("jar", "cf", jarPath.toString(), "-C", classesDir.toString(), ".")
                .redirectErrorStream(true).start().waitFor();

        return jarPath;
    }

    private Path createTestWar() throws Exception {
        Path libJar = createCompiledJar("dep.jar", "DepClass");

        Path warStaging = tempDir.resolve("war-staging");
        Path webInfClasses = warStaging.resolve("WEB-INF/classes/com/example");
        Path webInfLib = warStaging.resolve("WEB-INF/lib");
        Files.createDirectories(webInfClasses);
        Files.createDirectories(webInfLib);

        Files.writeString(webInfClasses.resolve("Servlet.java"), """
                package com.example;
                public class Servlet {
                    public void doGet() {}
                }
                """);

        new ProcessBuilder("javac", "-d", warStaging.resolve("WEB-INF/classes").toString(),
                webInfClasses.resolve("Servlet.java").toString())
                .redirectErrorStream(true).start().waitFor();

        Files.deleteIfExists(webInfClasses.resolve("Servlet.java"));

        Files.copy(libJar, webInfLib.resolve("dep.jar"));

        Path warPath = tempDir.resolve("test.war");
        new ProcessBuilder("jar", "cf", warPath.toString(), "-C", warStaging.toString(), ".")
                .redirectErrorStream(true).start().waitFor();

        return warPath;
    }
}

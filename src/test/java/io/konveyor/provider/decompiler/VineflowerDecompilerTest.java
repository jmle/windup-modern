package io.konveyor.provider.decompiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VineflowerDecompiler}: single JAR decompilation, parallel multi-JAR
 * decompilation, and error handling for nonexistent and empty JARs. Compiles real
 * Java source to bytecode at test time to produce authentic class files.
 */
class VineflowerDecompilerTest {

    @TempDir
    Path tempDir;

    Path testJar;
    VineflowerDecompiler decompiler;

    @BeforeEach
    void setUp() throws Exception {
        decompiler = new VineflowerDecompiler(2);
        testJar = createTestJar();
    }

    @Test
    void shouldDecompileJar() {
        Path outputDir = tempDir.resolve("output");
        DecompileResult result = decompiler.decompileJar(testJar, outputDir);

        assertThat(result.classCount()).isGreaterThan(0);
        assertThat(result.errorCount()).isEqualTo(0);
        assertThat(result.hasOutput()).isTrue();

        Path decompiledFile = outputDir.resolve("com/example/Hello.java");
        assertThat(decompiledFile).exists();
        try {
            String content = Files.readString(decompiledFile);
            assertThat(content).contains("class Hello");
            assertThat(content).contains("greet");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldDecompileMultipleJarsInParallel() throws Exception {
        Path jar2 = createTestJar("com/example/World.class", "World");
        List<DecompileResult> results = decompiler.decompileJars(
                List.of(testJar, jar2), tempDir.resolve("parallel-output"));

        assertThat(results).hasSize(2);
        assertThat(results.stream().mapToInt(DecompileResult::classCount).sum()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldHandleNonExistentJar() {
        DecompileResult result = decompiler.decompileJar(
                tempDir.resolve("nonexistent.jar"), tempDir.resolve("output"));

        assertThat(result.classCount()).isEqualTo(0);
        assertThat(result.errorCount()).isGreaterThan(0);
        assertThat(result.hasOutput()).isFalse();
    }

    @Test
    void shouldHandleEmptyJar() throws Exception {
        Path emptyJar = tempDir.resolve("empty.jar");
        try (FileSystem zipFs = FileSystems.newFileSystem(emptyJar, Map.of("create", "true"))) {
            // empty JAR
        }

        DecompileResult result = decompiler.decompileJar(emptyJar, tempDir.resolve("output"));
        assertThat(result.classCount()).isEqualTo(0);
    }

    private Path createTestJar() throws Exception {
        return createTestJar("com/example/Hello.class", "Hello");
    }

    private Path createTestJar(String classEntry, String className) throws Exception {
        Path sourceDir = tempDir.resolve("src-" + className);
        Path packageDir = sourceDir.resolve("com/example");
        Files.createDirectories(packageDir);

        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, """
                package com.example;

                public class %s {
                    private String name;

                    public %s(String name) {
                        this.name = name;
                    }

                    public String greet() {
                        return "Hello, " + name + "!";
                    }
                }
                """.formatted(className, className));

        Path classesDir = tempDir.resolve("classes-" + className);
        Files.createDirectories(classesDir);

        ProcessBuilder pb = new ProcessBuilder(
                "javac", "-d", classesDir.toString(), sourceFile.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("javac failed: " + output);
        }

        Path jarPath = tempDir.resolve(className.toLowerCase() + ".jar");
        ProcessBuilder jarPb = new ProcessBuilder(
                "jar", "cf", jarPath.toString(), "-C", classesDir.toString(), ".");
        jarPb.redirectErrorStream(true);
        Process jarProc = jarPb.start();
        jarProc.waitFor();

        return jarPath;
    }
}

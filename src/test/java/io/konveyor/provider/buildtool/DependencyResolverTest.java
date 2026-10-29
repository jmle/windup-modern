package io.konveyor.provider.buildtool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link DependencyResolver#resolveFromDownloadedSources} (MTA-8217): consuming the
 * {@code -sources.jar}s that the Gradle task drops into {@code build/downloads}.
 */
class DependencyResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveFromDownloadedSourcesExtractsMatchingSourceJar() throws Exception {
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(downloads);
        Path srcJar = downloads.resolve("foo-1.0-sources.jar");
        try (OutputStream os = Files.newOutputStream(srcJar); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("com/foo/Bar.java"));
            zos.write("package com.foo; public class Bar {}".getBytes());
            zos.closeEntry();
        }

        BuildTool.ResolvedDependency dep = new BuildTool.ResolvedDependency(
                "com.foo", "foo", "1.0", null, "compile", null, false, false, "build.gradle");

        DependencyResolver.ResolveResult result = new DependencyResolver()
                .resolveFromDownloadedSources(List.of(dep), downloads, tempDir.resolve("work"));

        assertThat(result.sourceDirs()).hasSize(1);
        assertThat(result.sourceDirs().get(0).resolve("com/foo/Bar.java")).exists();
        assertThat(result.decompiledCount()).isZero();
    }

    @Test
    void resolveFromDownloadedSourcesSkipsDepWithNoSourceAndNoBinary() {
        // no source jar in downloads, and no binary jar on disk -> nothing to extract or decompile
        BuildTool.ResolvedDependency dep = new BuildTool.ResolvedDependency(
                "com.foo", "missing", "1.0", null, "compile", null, false, false, "build.gradle");

        DependencyResolver.ResolveResult result = new DependencyResolver()
                .resolveFromDownloadedSources(List.of(dep), tempDir.resolve("empty-downloads"),
                        tempDir.resolve("work"));

        assertThat(result.sourceDirs()).isEmpty();
        assertThat(result.decompiledCount()).isZero();
    }
}

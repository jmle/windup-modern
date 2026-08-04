package org.jboss.windup.engine.archive;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.ArchiveModel;
import org.jboss.windup.model.ArchiveType;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveExtractionProviderTest {

    @TempDir
    Path tempDir;

    private ArchiveExtractionProvider provider;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        provider = new ArchiveExtractionProvider();
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
    }

    // -- metadata tests --

    @Test
    void metadataIsCorrect() {
        var meta = provider.getMetadata();
        assertThat(meta.id()).isEqualTo("archive-extraction");
        assertThat(meta.phase()).isEqualTo(Phase.ARCHIVE_EXTRACTION);
        assertThat(meta.executeAfter()).contains("file-discovery");
    }

    @Test
    void rulesListIsNotEmpty() {
        assertThat(provider.getRules()).hasSize(1);
    }

    // -- condition tests --

    @Test
    void conditionReturnsFalseWhenNoArchives() {
        AnalysisRun run = createRun();
        ConditionResult result = provider.checkArchivesExist(run);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionReturnsTrueWhenArchivesExist() throws IOException {
        Path jarPath = createSimpleJar("test.jar", "com/example/Hello.class", "hello class bytes");
        AnalysisRun run = createRun();
        ArchiveModel archive = new ArchiveModel(jarPath);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        ConditionResult result = provider.checkArchivesExist(run);
        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    // -- extraction tests --

    @Test
    void extractsSimpleJarAndCreatesFileModels() throws IOException {
        Path jarPath = createSimpleJar("app.jar",
                "com/example/Main.class", "main class",
                "com/example/Helper.java", "helper source",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");

        AnalysisRun run = createRun();
        ArchiveModel archive = new ArchiveModel(jarPath);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);
        run.getContext().files().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        // Verify entries were added to the archive model
        assertThat(archive.getEntries()).isNotEmpty();

        // Verify different file types are classified
        boolean hasClassFile = archive.getEntries().stream()
                .anyMatch(f -> f.getFileType() == FileType.JAVA_CLASS);
        boolean hasJavaSource = archive.getEntries().stream()
                .anyMatch(f -> f.getFileType() == FileType.JAVA_SOURCE);
        boolean hasManifest = archive.getEntries().stream()
                .anyMatch(f -> f.getFileType() == FileType.MANIFEST);

        assertThat(hasClassFile).isTrue();
        assertThat(hasJavaSource).isTrue();
        assertThat(hasManifest).isTrue();

        // Verify files are registered in context
        assertThat(run.getContext().files().size()).isGreaterThan(1);
    }

    @Test
    void extractedFilesExistOnDisk() throws IOException {
        Path jarPath = createSimpleJar("disk-check.jar",
                "data/config.xml", "<config/>",
                "data/query.sql", "SELECT 1");

        AnalysisRun run = createRun();
        ArchiveModel archive = new ArchiveModel(jarPath);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        // All non-directory entries should exist on disk
        for (FileModel entry : archive.getEntries()) {
            if (!entry.isDirectory()) {
                assertThat(Files.exists(entry.getFilePath()))
                        .as("Extracted file should exist: " + entry.getFilePath())
                        .isTrue();
            }
        }
    }

    @Test
    void handlesNestedArchives() throws IOException {
        // Create an inner JAR
        Path innerJar = createSimpleJar("inner.jar",
                "org/inner/Nested.class", "nested bytes");

        // Create an outer JAR that embeds the inner JAR
        Path outerJar = createJarWithNestedArchive("outer.jar", innerJar);

        AnalysisRun run = createRun();
        ArchiveModel archive = new ArchiveModel(outerJar);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        // The outer archive should contain the nested archive as an entry
        boolean hasNestedArchive = archive.getEntries().stream()
                .anyMatch(f -> f instanceof ArchiveModel);
        assertThat(hasNestedArchive).isTrue();

        // The nested archive should itself have entries
        ArchiveModel nested = archive.getEntries().stream()
                .filter(f -> f instanceof ArchiveModel)
                .map(f -> (ArchiveModel) f)
                .findFirst()
                .orElseThrow();
        assertThat(nested.getEntries()).isNotEmpty();
        assertThat(nested.getArchiveType()).isEqualTo(ArchiveType.JAR);

        // Total archives: original (1) + nested (1) = at least 2
        assertThat(run.getContext().archives().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void respectsMaxDepthLimit() throws IOException {
        // Create a deeply nested chain: level3.jar inside level2.jar inside level1.jar
        Path level3 = createSimpleJar("level3.jar",
                "deep/File.class", "deep content");
        Path level2 = createJarWithNestedArchive("level2.jar", level3);
        Path level1 = createJarWithNestedArchive("level1.jar", level2);

        // Set max depth to 1 so only the first level is extracted
        AnalysisRun run = createRun(1);
        ArchiveModel archive = new ArchiveModel(level1);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        // The immediate nested archive (level2) should be registered
        // but level3 inside level2 should NOT be extracted (depth limit hit)
        long nestedArchiveCount = archive.getEntries().stream()
                .filter(f -> f instanceof ArchiveModel)
                .count();
        assertThat(nestedArchiveCount).isEqualTo(1);

        ArchiveModel nestedLevel2 = archive.getEntries().stream()
                .filter(f -> f instanceof ArchiveModel)
                .map(f -> (ArchiveModel) f)
                .findFirst()
                .orElseThrow();

        // level2's nested level3 should NOT have been extracted (depth was 1)
        // level2 entries are empty because extraction was blocked at depth 1
        assertThat(nestedLevel2.getEntries()).isEmpty();
    }

    @Test
    void cancellationStopsExtraction() throws IOException {
        Path jarPath = createSimpleJar("cancel-test.jar",
                "a/File1.class", "content1",
                "b/File2.class", "content2");

        AnalysisRun run = createRun();
        run.cancel(); // Pre-cancel

        ArchiveModel archive = new ArchiveModel(jarPath);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        // No entries should have been created because run was cancelled
        assertThat(archive.getEntries()).isEmpty();
    }

    // -- archive identifier tests --

    @Test
    void identifiesArchiveFromManifest() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Bundle-SymbolicName", "org.apache.commons.lang3");
        attrs.putValue("Bundle-Version", "3.12.0");
        attrs.putValue("Bundle-Vendor", "The Apache Software Foundation");

        Path jarPath = createJarWithManifest("commons-lang3.jar", manifest);

        ArchiveModel archive = new ArchiveModel(jarPath);
        ArchiveIdentifier.identify(archive);

        assertThat(archive.isIdentified()).isTrue();
        assertThat(archive.getIdentifiedGroupId()).isEqualTo("org.apache.commons");
        assertThat(archive.getIdentifiedArtifactId()).isEqualTo("lang3");
        assertThat(archive.getIdentifiedVersion()).isEqualTo("3.12.0");
        assertThat(archive.getOrganizationName()).isEqualTo("The Apache Software Foundation");
    }

    @Test
    void identifiesArchiveFromImplementationAttrs() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Implementation-Title", "guava");
        attrs.putValue("Implementation-Version", "31.1-jre");
        attrs.putValue("Implementation-Vendor", "Google LLC");

        Path jarPath = createJarWithManifest("guava.jar", manifest);

        ArchiveModel archive = new ArchiveModel(jarPath);
        ArchiveIdentifier.identify(archive);

        assertThat(archive.isIdentified()).isTrue();
        assertThat(archive.getIdentifiedArtifactId()).isEqualTo("guava");
        assertThat(archive.getIdentifiedVersion()).isEqualTo("31.1-jre");
        assertThat(archive.getOrganizationName()).isEqualTo("Google LLC");
    }

    @Test
    void noIdentificationWithoutManifest() throws IOException {
        Path jarPath = createSimpleJar("no-manifest.jar",
                "com/example/Foo.class", "foo bytes");

        ArchiveModel archive = new ArchiveModel(jarPath);
        ArchiveIdentifier.identify(archive);

        assertThat(archive.isIdentified()).isFalse();
        assertThat(archive.getIdentifiedArtifactId()).isNull();
    }

    // -- file classification utility tests --

    @Test
    void classifiesFileTypesCorrectly() {
        assertThat(ArchiveExtractionProvider.classifyFile("Main.java")).isEqualTo(FileType.JAVA_SOURCE);
        assertThat(ArchiveExtractionProvider.classifyFile("Main.class")).isEqualTo(FileType.JAVA_CLASS);
        assertThat(ArchiveExtractionProvider.classifyFile("beans.xml")).isEqualTo(FileType.XML);
        assertThat(ArchiveExtractionProvider.classifyFile("app.yaml")).isEqualTo(FileType.YAML);
        assertThat(ArchiveExtractionProvider.classifyFile("app.yml")).isEqualTo(FileType.YAML);
        assertThat(ArchiveExtractionProvider.classifyFile("db.properties")).isEqualTo(FileType.PROPERTIES);
        assertThat(ArchiveExtractionProvider.classifyFile("index.jsp")).isEqualTo(FileType.JSP);
        assertThat(ArchiveExtractionProvider.classifyFile("page.xhtml")).isEqualTo(FileType.XHTML);
        assertThat(ArchiveExtractionProvider.classifyFile("index.html")).isEqualTo(FileType.HTML);
        assertThat(ArchiveExtractionProvider.classifyFile("style.css")).isEqualTo(FileType.CSS);
        assertThat(ArchiveExtractionProvider.classifyFile("app.js")).isEqualTo(FileType.JAVASCRIPT);
        assertThat(ArchiveExtractionProvider.classifyFile("schema.sql")).isEqualTo(FileType.SQL);
        assertThat(ArchiveExtractionProvider.classifyFile("META-INF/MANIFEST.MF")).isEqualTo(FileType.MANIFEST);
        assertThat(ArchiveExtractionProvider.classifyFile("unknown.dat")).isEqualTo(FileType.OTHER);
    }

    @Test
    void determinesArchiveTypes() {
        assertThat(ArchiveExtractionProvider.determineArchiveType("app.jar")).isEqualTo(ArchiveType.JAR);
        assertThat(ArchiveExtractionProvider.determineArchiveType("app.war")).isEqualTo(ArchiveType.WAR);
        assertThat(ArchiveExtractionProvider.determineArchiveType("app.ear")).isEqualTo(ArchiveType.EAR);
        assertThat(ArchiveExtractionProvider.determineArchiveType("app.rar")).isEqualTo(ArchiveType.RAR);
        assertThat(ArchiveExtractionProvider.determineArchiveType("app.sar")).isEqualTo(ArchiveType.SAR);
        assertThat(ArchiveExtractionProvider.determineArchiveType("bundle.zip")).isEqualTo(ArchiveType.ZIP);
        assertThat(ArchiveExtractionProvider.determineArchiveType("readme.txt")).isEqualTo(ArchiveType.OTHER);
    }

    @Test
    void isArchiveFileDetectsCorrectly() {
        assertThat(ArchiveExtractionProvider.isArchiveFile("lib.jar")).isTrue();
        assertThat(ArchiveExtractionProvider.isArchiveFile("app.WAR")).isTrue();
        assertThat(ArchiveExtractionProvider.isArchiveFile("deploy.EAR")).isTrue();
        assertThat(ArchiveExtractionProvider.isArchiveFile("file.txt")).isFalse();
        assertThat(ArchiveExtractionProvider.isArchiveFile(null)).isFalse();
    }

    @Test
    void extractionWithManifestIdentifiesArchive() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Bundle-SymbolicName", "com.example.mylib");
        attrs.putValue("Bundle-Version", "2.0.0");

        Path jarPath = createJarWithManifest("mylib.jar", manifest,
                "com/example/Service.class", "service bytes");

        AnalysisRun run = createRun();
        ArchiveModel archive = new ArchiveModel(jarPath);
        archive.setArchiveType(ArchiveType.JAR);
        run.getContext().archives().register(archive);

        provider.extractArchives(run, ConditionResult.match(List.of(archive)));

        assertThat(archive.isIdentified()).isTrue();
        assertThat(archive.getIdentifiedGroupId()).isEqualTo("com.example");
        assertThat(archive.getIdentifiedArtifactId()).isEqualTo("mylib");
        assertThat(archive.getIdentifiedVersion()).isEqualTo("2.0.0");
    }

    // -- helpers --

    private AnalysisRun createRun() {
        return createRun(ArchiveExtractionProvider.DEFAULT_MAX_DEPTH);
    }

    private AnalysisRun createRun(int maxDepth) {
        var builder = AnalysisConfiguration.builder()
                .inputPath(tempDir)
                .outputDirectory(outputDir);
        if (maxDepth != ArchiveExtractionProvider.DEFAULT_MAX_DEPTH) {
            builder.option("archiveExtractionMaxDepth", maxDepth);
        }
        return new AnalysisRun(new AnalysisContext(), builder.build());
    }

    /**
     * Creates a JAR containing the given entries (pairs of name, content).
     */
    private Path createSimpleJar(String jarName, String... nameContentPairs) throws IOException {
        Path jarPath = tempDir.resolve(jarName);
        Set<String> createdDirs = new HashSet<>();
        try (OutputStream fos = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(fos)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                String name = nameContentPairs[i];
                String content = nameContentPairs[i + 1];
                createParentDirEntries(jos, name, createdDirs);
                jos.putNextEntry(new JarEntry(name));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    /**
     * Creates a JAR with a specific manifest and optional extra entries.
     */
    private Path createJarWithManifest(String jarName, Manifest manifest,
                                       String... nameContentPairs) throws IOException {
        Path jarPath = tempDir.resolve(jarName);
        Set<String> createdDirs = new HashSet<>();
        try (OutputStream fos = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(fos, manifest)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                String name = nameContentPairs[i];
                String content = nameContentPairs[i + 1];
                createParentDirEntries(jos, name, createdDirs);
                jos.putNextEntry(new JarEntry(name));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    /**
     * Creates a JAR that contains a regular file plus a nested archive.
     */
    private Path createJarWithNestedArchive(String outerName, Path innerArchive) throws IOException {
        Path outerPath = tempDir.resolve(outerName);
        try (OutputStream fos = Files.newOutputStream(outerPath);
             JarOutputStream jos = new JarOutputStream(fos)) {

            // Add a regular file entry
            jos.putNextEntry(new JarEntry("README.txt"));
            jos.write("readme content".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add a directory entry for lib/
            jos.putNextEntry(new JarEntry("lib/"));
            jos.closeEntry();

            // Embed the inner archive
            String innerName = "lib/" + innerArchive.getFileName().toString();
            jos.putNextEntry(new JarEntry(innerName));
            jos.write(Files.readAllBytes(innerArchive));
            jos.closeEntry();
        }
        return outerPath;
    }

    private void createParentDirEntries(JarOutputStream jos, String entryName,
                                        Set<String> createdDirs) throws IOException {
        int lastSlash = entryName.lastIndexOf('/');
        if (lastSlash > 0) {
            String[] parts = entryName.substring(0, lastSlash).split("/");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                sb.append(part).append('/');
                String dirName = sb.toString();
                if (createdDirs.add(dirName)) {
                    jos.putNextEntry(new JarEntry(dirName));
                    jos.closeEntry();
                }
            }
        }
    }
}

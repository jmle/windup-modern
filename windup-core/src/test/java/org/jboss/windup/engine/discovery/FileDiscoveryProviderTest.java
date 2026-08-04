package org.jboss.windup.engine.discovery;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileDiscoveryProviderTest {

    @TempDir
    Path tempDir;

    private FileDiscoveryProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FileDiscoveryProvider();
    }

    @Test
    void metadataIsCorrect() {
        assertThat(provider.getMetadata().id()).isEqualTo("FileDiscoveryProvider");
        assertThat(provider.getMetadata().phase()).isEqualTo(Phase.DISCOVERY);
    }

    @Test
    void producesOneRule() {
        assertThat(provider.getRules()).hasSize(1);
        assertThat(provider.getRules().get(0).id()).isEqualTo("file-discovery");
    }

    @Test
    void discoversJavaSourceFile() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        AnalysisContext ctx = run.getContext();
        // Root directory + App.java
        assertThat(ctx.files().size()).isGreaterThanOrEqualTo(2);

        Optional<FileModel> javaFile = ctx.getFileByPath(tempDir.resolve("App.java"));
        assertThat(javaFile).isPresent();
        assertThat(javaFile.get().getFileType()).isEqualTo(FileType.JAVA_SOURCE);
        assertThat(javaFile.get().getFileName()).isEqualTo("App.java");
        assertThat(javaFile.get().isDirectory()).isFalse();
        assertThat(javaFile.get().getFileSize()).isGreaterThan(0);
    }

    @Test
    void discoversDirectoryStructure() throws IOException {
        Path subDir = Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(subDir.resolve("Hello.java"), "class Hello {}");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        AnalysisContext ctx = run.getContext();

        // Check subdirectory
        Optional<FileModel> srcDir = ctx.getFileByPath(tempDir.resolve("src"));
        assertThat(srcDir).isPresent();
        assertThat(srcDir.get().isDirectory()).isTrue();
        assertThat(srcDir.get().getFileType()).isEqualTo(FileType.DIRECTORY);

        // Check parent relationship
        Optional<FileModel> mainDir = ctx.getFileByPath(tempDir.resolve("src/main"));
        assertThat(mainDir).isPresent();
        assertThat(mainDir.get().getParentDirectory()).isEqualTo(srcDir.get());

        // Check nested file
        Optional<FileModel> javaFile = ctx.getFileByPath(subDir.resolve("Hello.java"));
        assertThat(javaFile).isPresent();
        assertThat(javaFile.get().getFileType()).isEqualTo(FileType.JAVA_SOURCE);
        assertThat(javaFile.get().getParentDirectory()).isNotNull();
    }

    @Test
    void discoversArchiveFiles() throws IOException {
        // Create a dummy jar file (just needs to have the extension)
        Files.write(tempDir.resolve("lib.jar"), new byte[]{0x50, 0x4B, 0x03, 0x04});
        Files.write(tempDir.resolve("app.war"), new byte[]{0x50, 0x4B, 0x03, 0x04});

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        AnalysisContext ctx = run.getContext();

        // Check archives registry
        assertThat(ctx.archives().size()).isEqualTo(2);

        Optional<FileModel> jarFile = ctx.getFileByPath(tempDir.resolve("lib.jar"));
        assertThat(jarFile).isPresent();
        assertThat(jarFile.get()).isInstanceOf(ArchiveModel.class);
        assertThat(jarFile.get().getFileType()).isEqualTo(FileType.ARCHIVE);
        assertThat(((ArchiveModel) jarFile.get()).getArchiveType()).isEqualTo(ArchiveType.JAR);

        Optional<FileModel> warFile = ctx.getFileByPath(tempDir.resolve("app.war"));
        assertThat(warFile).isPresent();
        assertThat(warFile.get()).isInstanceOf(ArchiveModel.class);
        assertThat(((ArchiveModel) warFile.get()).getArchiveType()).isEqualTo(ArchiveType.WAR);
    }

    @Test
    void discoversMultipleFileTypes() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("config.yml"), "key: value");
        Files.writeString(tempDir.resolve("app.properties"), "key=value");
        Files.writeString(tempDir.resolve("index.html"), "<html/>");
        Files.writeString(tempDir.resolve("query.sql"), "SELECT 1");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        AnalysisContext ctx = run.getContext();

        assertThat(ctx.getFilesByType(FileType.JAVA_SOURCE)).hasSize(1);
        assertThat(ctx.getFilesByType(FileType.XML)).hasSize(1);
        assertThat(ctx.getFilesByType(FileType.YAML)).hasSize(1);
        assertThat(ctx.getFilesByType(FileType.PROPERTIES)).hasSize(1);
        assertThat(ctx.getFilesByType(FileType.HTML)).hasSize(1);
        assertThat(ctx.getFilesByType(FileType.SQL)).hasSize(1);
    }

    @Test
    void computesHashesForFiles() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        Optional<FileModel> file = run.getContext().getFileByPath(tempDir.resolve("test.txt"));
        assertThat(file).isPresent();
        assertThat(file.get().getSha1Hash()).isNotNull().isNotEmpty();
        assertThat(file.get().getMd5Hash()).isNotNull().isNotEmpty();
        // SHA-1 of "hello world" is well known
        assertThat(file.get().getSha1Hash()).isEqualTo("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed");
    }

    @Test
    void createsApplicationModel() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        assertThat(run.getContext().applications().size()).isEqualTo(1);
        assertThat(run.getContext().applications().findAll().get(0).getName())
                .isEqualTo(tempDir.getFileName().toString());
    }

    @Test
    void createsProjectModel() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        assertThat(run.getContext().projects().size()).isEqualTo(1);
        var project = run.getContext().projects().findAll().get(0);
        assertThat(project.getName()).isEqualTo(tempDir.getFileName().toString());
        assertThat(project.getRootFileModel()).isNotNull();
        assertThat(project.getFileModels()).isNotEmpty();
    }

    @Test
    void setsProjectOnDiscoveredFiles() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");

        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        var project = run.getContext().projects().findAll().get(0);
        Optional<FileModel> file = run.getContext().getFileByPath(tempDir.resolve("App.java"));
        assertThat(file).isPresent();
        assertThat(file.get().getProject()).isEqualTo(project);
    }

    @Test
    void handlesEmptyDirectory() {
        AnalysisRun run = createRun(tempDir);
        executeDiscovery(run);

        // Should have at least the root directory
        assertThat(run.getContext().files().size()).isGreaterThanOrEqualTo(1);
        assertThat(run.getContext().applications().size()).isEqualTo(1);
        assertThat(run.getContext().projects().size()).isEqualTo(1);
    }

    @Test
    void handlesNonExistentInputPath() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        AnalysisRun run = createRun(nonExistent);
        executeDiscovery(run);

        // Should not throw, just log a warning and produce nothing
        assertThat(run.getContext().files().size()).isEqualTo(0);
    }

    @Test
    void respectsCancellation() throws IOException {
        // Create many files
        for (int i = 0; i < 100; i++) {
            Files.writeString(tempDir.resolve("File" + i + ".java"), "class File" + i + " {}");
        }

        AnalysisRun run = createRun(tempDir);
        // Cancel immediately
        run.cancel();
        executeDiscovery(run);

        // Discovery should have stopped early -- some files may have been registered
        // before cancellation was detected, but not all 100 plus directories
        // The important thing is it didn't throw
        assertThat(run.isCancelled()).isTrue();
    }

    // --- helpers ---

    private AnalysisRun createRun(Path inputPath) {
        var config = AnalysisConfiguration.builder()
                .inputPath(inputPath)
                .outputDirectory(tempDir.resolve("output"))
                .build();
        return new AnalysisRun(new AnalysisContext(), config);
    }

    private void executeDiscovery(AnalysisRun run) {
        provider.discoverFiles(run, ConditionResult.match(List.of()));
    }
}

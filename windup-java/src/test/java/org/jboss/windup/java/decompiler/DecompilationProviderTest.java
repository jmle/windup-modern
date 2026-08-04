package org.jboss.windup.java.decompiler;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DecompilationProvider}: metadata, condition evaluation,
 * and rule structure.
 */
class DecompilationProviderTest {

    @TempDir
    Path tempDir;

    private DecompilationProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new DecompilationProvider();
        // Inject a stub DecompilerService that returns fixed output
        Field field = DecompilationProvider.class.getDeclaredField("decompilerService");
        field.setAccessible(true);
        field.set(provider, new StubDecompilerService());
    }

    @Test
    void metadataHasCorrectPhaseAndId() {
        RuleProviderMetadata meta = provider.getMetadata();

        assertThat(meta.id()).isEqualTo("decompilation-provider");
        assertThat(meta.phase()).isEqualTo(Phase.DECOMPILATION);
        assertThat(meta.tags()).contains("java", "decompilation");
        assertThat(meta.executeAfter()).contains("archive-extraction");
    }

    @Test
    void providerProducesSingleRule() {
        assertThat(provider.getRules()).hasSize(1);
        assertThat(provider.getRules().get(0).id()).isEqualTo("decompile-class-files");
    }

    @Test
    void conditionMatchesWhenClassFilesPresent() {
        AnalysisRun run = createRun();
        FileModel classFile = new FileModel(Path.of("/app/Foo.class"));
        classFile.setFileType(FileType.JAVA_CLASS);
        run.getContext().files().register(classFile);

        ConditionResult result = provider.checkForClassFiles(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void conditionDoesNotMatchWhenNoClassFiles() {
        AnalysisRun run = createRun();
        FileModel javaFile = new FileModel(Path.of("/app/Foo.java"));
        javaFile.setFileType(FileType.JAVA_SOURCE);
        run.getContext().files().register(javaFile);

        ConditionResult result = provider.checkForClassFiles(run);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionExcludesClassFilesWithExistingSource() {
        AnalysisRun run = createRun();

        FileModel classFile = new FileModel(Path.of("/app/Foo.class"));
        classFile.setFileType(FileType.JAVA_CLASS);
        run.getContext().files().register(classFile);

        // Register a corresponding .java source
        FileModel sourceFile = new FileModel(Path.of("/app/Foo.java"));
        sourceFile.setFileType(FileType.JAVA_SOURCE);
        run.getContext().files().register(sourceFile);

        ConditionResult result = provider.checkForClassFiles(run);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionMatchesClassFilesWithoutCorrespondingSource() {
        AnalysisRun run = createRun();

        // Class file with a corresponding source - should be excluded
        FileModel classFileWithSource = new FileModel(Path.of("/app/Foo.class"));
        classFileWithSource.setFileType(FileType.JAVA_CLASS);
        run.getContext().files().register(classFileWithSource);

        FileModel sourceFile = new FileModel(Path.of("/app/Foo.java"));
        sourceFile.setFileType(FileType.JAVA_SOURCE);
        run.getContext().files().register(sourceFile);

        // Class file without a corresponding source - should be matched
        FileModel classFileWithoutSource = new FileModel(Path.of("/app/Bar.class"));
        classFileWithoutSource.setFileType(FileType.JAVA_CLASS);
        run.getContext().files().register(classFileWithoutSource);

        ConditionResult result = provider.checkForClassFiles(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    // --- helpers ---

    private AnalysisRun createRun() {
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir)
                .outputDirectory(tempDir.resolve("output"))
                .build();
        return new AnalysisRun(new AnalysisContext(), config);
    }

    /**
     * A simple stub that returns a fixed decompiled output for any class file.
     */
    private static class StubDecompilerService implements DecompilerService {
        @Override
        public Optional<String> decompile(Path classFile) {
            return Optional.of("// decompiled from " + classFile.getFileName());
        }

        @Override
        public java.util.Map<String, String> decompileArchive(Path archivePath, Path outputDir) {
            return java.util.Map.of();
        }
    }
}

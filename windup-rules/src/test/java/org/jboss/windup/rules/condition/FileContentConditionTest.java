package org.jboss.windup.rules.condition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FileContentCondition}.
 */
class FileContentConditionTest {

    private AnalysisContext context;
    private AnalysisRun run;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir)
                .outputDirectory(tempDir.resolve("output"))
                .build();
        run = new AnalysisRun(context, config);
    }

    // ---- Filename glob compilation tests ----

    @Test
    void filenameGlobMatchesJavaFiles() {
        Pattern p = FileContentCondition.compileFilenameGlob("*.java");
        assertThat(p.matcher("Foo.java").matches()).isTrue();
        assertThat(p.matcher("bar.java").matches()).isTrue();
        assertThat(p.matcher("Foo.xml").matches()).isFalse();
        assertThat(p.matcher("java").matches()).isFalse();
    }

    @Test
    void filenameGlobMatchesQuestionMark() {
        Pattern p = FileContentCondition.compileFilenameGlob("?.txt");
        assertThat(p.matcher("a.txt").matches()).isTrue();
        assertThat(p.matcher("ab.txt").matches()).isFalse();
    }

    @Test
    void filenameGlobExactMatch() {
        Pattern p = FileContentCondition.compileFilenameGlob("pom.xml");
        assertThat(p.matcher("pom.xml").matches()).isTrue();
        assertThat(p.matcher("pom.yaml").matches()).isFalse();
    }

    // ---- Condition evaluation tests ----

    @Test
    void matchesPatternInFileContent() throws IOException {
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(javaFile, """
                import javax.ejb.Stateless;
                import javax.ejb.Stateful;
                import java.util.List;
                """);
        registerFile(javaFile);

        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void filenameFilterRestrictsSearch() throws IOException {
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(javaFile, "javax.ejb.Stateless");
        registerFile(javaFile);

        Path xmlFile = tempDir.resolve("config.xml");
        Files.writeString(xmlFile, "javax.ejb.Stateless");
        registerFile(xmlFile);

        // Only match in .java files
        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", "*.java");
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
        FileContentCondition.FileContentMatch match =
                (FileContentCondition.FileContentMatch) result.items().get(0);
        assertThat(match.getFile().getFileName()).isEqualTo("Test.java");
    }

    @Test
    void noMatchReturnsNoMatch() throws IOException {
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(javaFile, "import java.util.List;");
        registerFile(javaFile);

        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void emptyContextReturnsNoMatch() {
        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void nullRunReturnsNoMatch() {
        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", null);
        ConditionResult result = condition.evaluate(null);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void matchIncludesCorrectLineAndColumn() throws IOException {
        Path file = tempDir.resolve("App.java");
        Files.writeString(file, """
                package com.example;
                import javax.ejb.Stateless;
                public class App {}
                """);
        registerFile(file);

        FileContentCondition condition = new FileContentCondition("javax\\.ejb", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
        FileContentCondition.FileContentMatch match =
                (FileContentCondition.FileContentMatch) result.items().get(0);
        assertThat(match.getLineNumber()).isEqualTo(2); // second line
        assertThat(match.getMatchedText()).isEqualTo("javax.ejb");
    }

    @Test
    void multipleMatchesOnSameLine() throws IOException {
        Path file = tempDir.resolve("Multi.java");
        Files.writeString(file, "foo bar foo baz foo");
        registerFile(file);

        FileContentCondition condition = new FileContentCondition("foo", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(3);
    }

    @Test
    void directoriesAreSkipped() throws IOException {
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);
        FileModel dirModel = new FileModel(dir);
        dirModel.setDirectory(true);
        context.files().register(dirModel);

        FileContentCondition condition = new FileContentCondition("anything", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void nullPatternThrowsException() {
        assertThatThrownBy(() -> new FileContentCondition(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankPatternThrowsException() {
        assertThatThrownBy(() -> new FileContentCondition("   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gettersReturnCorrectValues() {
        FileContentCondition condition = new FileContentCondition("javax\\.ejb\\.", "*.java");
        assertThat(condition.getContentPatternSource()).isEqualTo("javax\\.ejb\\.");
        assertThat(condition.getFilenameGlob()).isEqualTo("*.java");
    }

    @Test
    void matchToStringIncludesDetails() throws IOException {
        Path file = tempDir.resolve("Info.java");
        Files.writeString(file, "javax.ejb.Stateless");
        registerFile(file);

        FileContentCondition condition = new FileContentCondition("javax\\.ejb", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        FileContentCondition.FileContentMatch match =
                (FileContentCondition.FileContentMatch) result.items().get(0);
        assertThat(match.toString()).contains("javax.ejb");
        assertThat(match.toString()).contains("Info.java");
    }

    // ---- Helpers ----

    private void registerFile(Path path) {
        FileModel model = new FileModel(path);
        context.files().register(model);
    }
}

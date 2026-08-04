package org.jboss.windup.rules.action;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.reporting.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HintActionTest {

    @TempDir
    Path tempDir;

    private AnalysisContext context;
    private AnalysisRun run;

    @BeforeEach
    void setUp() {
        context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .build();
        run = new AnalysisRun(context, config);
    }

    @Test
    void shouldCreateHintForJavaClassReference() {
        FileModel file = new FileModel(Path.of("/src/com/example/MyBean.java"));
        JavaClassReference ref = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.ANNOTATION,
                42, 8);
        ref.setSourceFile(file);

        LinkModel link = new LinkModel("Migration Guide", "https://example.com/guide");
        HintAction action = new HintAction(
                "ejb-001", "EJB Migration", "Replace javax.ejb with jakarta.ejb",
                1, "mandatory", List.of(link));

        action.perform(run, ConditionResult.match(List.of(ref)));

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(1);

        InlineHintModel hint = registry.findAll().get(0);
        assertThat(hint.getTitle()).isEqualTo("EJB Migration");
        assertThat(hint.getHint()).isEqualTo("Replace javax.ejb with jakarta.ejb");
        assertThat(hint.getLineNumber()).isEqualTo(42);
        assertThat(hint.getColumnNumber()).isEqualTo(8);
        assertThat(hint.getEffort()).isEqualTo(EffortLevel.TRIVIAL);
        assertThat(hint.getSeverity()).isEqualTo(Severity.COMPLEX);
        assertThat(hint.getRuleId()).isEqualTo("ejb-001");
        assertThat(hint.getSourceFile()).isSameAs(file);
        assertThat(hint.getLinks()).hasSize(1);
        assertThat(hint.getLinks().get(0).title()).isEqualTo("Migration Guide");
        assertThat(hint.getLinks().get(0).url()).isEqualTo("https://example.com/guide");
    }

    @Test
    void shouldCreateHintForFileModel() {
        FileModel file = new FileModel(Path.of("/src/resources/config.xml"));

        HintAction action = new HintAction(
                "xml-001", "Config Update", "Update configuration format",
                3, "optional", List.of());

        action.perform(run, ConditionResult.match(List.of(file)));

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(1);

        InlineHintModel hint = registry.findAll().get(0);
        assertThat(hint.getTitle()).isEqualTo("Config Update");
        assertThat(hint.getLineNumber()).isEqualTo(0);
        assertThat(hint.getEffort()).isEqualTo(EffortLevel.COMPLEX);
        assertThat(hint.getSeverity()).isEqualTo(Severity.OPTIONAL);
        assertThat(hint.getSourceFile()).isSameAs(file);
    }

    @Test
    void shouldCreateHintPerMatchedItem() {
        FileModel file1 = new FileModel(Path.of("/src/A.java"));
        FileModel file2 = new FileModel(Path.of("/src/B.java"));

        JavaClassReference ref1 = new JavaClassReference("javax.ejb.Stateless",
                JavaClassReference.ReferenceType.ANNOTATION, 10, 1);
        ref1.setSourceFile(file1);

        JavaClassReference ref2 = new JavaClassReference("javax.ejb.EJB",
                JavaClassReference.ReferenceType.ANNOTATION, 20, 5);
        ref2.setSourceFile(file2);

        HintAction action = new HintAction(
                "ejb-002", "EJB Hint", "Migrate EJB",
                5, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref1, ref2)));

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(2);

        InlineHintModel hint1 = registry.findAll().get(0);
        assertThat(hint1.getLineNumber()).isEqualTo(10);
        assertThat(hint1.getSourceFile()).isSameAs(file1);

        InlineHintModel hint2 = registry.findAll().get(1);
        assertThat(hint2.getLineNumber()).isEqualTo(20);
        assertThat(hint2.getSourceFile()).isSameAs(file2);
    }

    @Test
    void shouldDoNothingOnNoMatch() {
        HintAction action = new HintAction(
                "rule-x", "Title", "Message", 1, null, List.of());

        action.perform(run, ConditionResult.noMatch());

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldDoNothingWithNullRun() {
        HintAction action = new HintAction(
                "rule-x", "Title", "Message", 1, null, List.of());

        // null run + noMatch should not throw
        action.perform(null, ConditionResult.noMatch());
    }

    @Test
    void shouldSkipJavaClassReferenceWithNullSourceFile() {
        JavaClassReference ref = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.IMPORT, 10, 0);
        // sourceFile is null

        HintAction action = new HintAction(
                "rule-y", "Hint", "Message", 1, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref)));

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldSkipUnknownItemTypes() {
        HintAction action = new HintAction(
                "rule-z", "Hint", "Message", 1, null, List.of());

        action.perform(run, ConditionResult.match(List.of("some-string", 42)));

        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldMapEffortLevelsCorrectly() {
        FileModel file = new FileModel(Path.of("/src/Test.java"));

        // effort 1 -> TRIVIAL
        verifyEffort(file, 1, EffortLevel.TRIVIAL);
        // effort 3 -> COMPLEX
        verifyEffort(file, 3, EffortLevel.COMPLEX);
        // effort 5 -> REDESIGN
        verifyEffort(file, 5, EffortLevel.REDESIGN);
        // effort 7 -> ARCHITECTURAL
        verifyEffort(file, 7, EffortLevel.ARCHITECTURAL);
        // effort 99 -> UNKNOWN
        verifyEffort(file, 99, EffortLevel.UNKNOWN);
    }

    private void verifyEffort(FileModel file, int points, EffortLevel expected) {
        AnalysisContext ctx = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .build();
        AnalysisRun testRun = new AnalysisRun(ctx, config);

        HintAction action = new HintAction("r", "T", "M", points, null, List.of());
        action.perform(testRun, ConditionResult.match(List.of(file)));

        InlineHintModel hint = ctx.getOrCreateRegistry(InlineHintModel.class).findAll().get(0);
        assertThat(hint.getEffort()).isEqualTo(expected);
    }

    @Test
    void shouldMapCategoryToSeverity() {
        assertThat(HintAction.parseSeverity(null)).isEqualTo(Severity.INFORMATION);
        assertThat(HintAction.parseSeverity("")).isEqualTo(Severity.INFORMATION);
        assertThat(HintAction.parseSeverity("INFORMATION")).isEqualTo(Severity.INFORMATION);
        assertThat(HintAction.parseSeverity("OPTIONAL")).isEqualTo(Severity.OPTIONAL);
        assertThat(HintAction.parseSeverity("mandatory")).isEqualTo(Severity.COMPLEX);
        assertThat(HintAction.parseSeverity("potential")).isEqualTo(Severity.TRIVIAL);
        assertThat(HintAction.parseSeverity("COMPLEX")).isEqualTo(Severity.COMPLEX);
        assertThat(HintAction.parseSeverity("unknown-value")).isEqualTo(Severity.INFORMATION);
    }

    @Test
    void shouldAttachMultipleLinks() {
        FileModel file = new FileModel(Path.of("/src/Test.java"));

        List<LinkModel> links = List.of(
                new LinkModel("Guide 1", "https://example.com/1"),
                new LinkModel("Guide 2", "https://example.com/2"),
                new LinkModel("Guide 3", "https://example.com/3"));

        HintAction action = new HintAction(
                "rule-links", "Title", "Message", 1, null, links);

        action.perform(run, ConditionResult.match(List.of(file)));

        InlineHintModel hint = context.getOrCreateRegistry(InlineHintModel.class).findAll().get(0);
        assertThat(hint.getLinks()).hasSize(3);
    }
}

package org.jboss.windup.rules.action;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.reporting.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationActionTest {

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
    void shouldCreateClassificationForFileModel() {
        FileModel file = new FileModel(Path.of("/src/resources/ejb-jar.xml"));

        LinkModel link = new LinkModel("EJB Guide", "https://example.com/ejb");
        ClassificationAction action = new ClassificationAction(
                "ejb-002", "EJB Deployment Descriptor",
                "This is an EJB deployment descriptor that needs updating.",
                3, "mandatory", List.of(link));

        action.perform(run, ConditionResult.match(List.of(file)));

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(1);

        ClassificationModel model = registry.findAll().get(0);
        assertThat(model.getTitle()).isEqualTo("EJB Deployment Descriptor");
        assertThat(model.getDescription()).isEqualTo("This is an EJB deployment descriptor that needs updating.");
        assertThat(model.getEffort()).isEqualTo(EffortLevel.COMPLEX);
        assertThat(model.getSeverity()).isEqualTo(Severity.COMPLEX);
        assertThat(model.getRuleId()).isEqualTo("ejb-002");
        assertThat(model.getSourceFile()).isSameAs(file);
        assertThat(model.getLinks()).hasSize(1);
        assertThat(model.getLinks().get(0).title()).isEqualTo("EJB Guide");
    }

    @Test
    void shouldCreateClassificationForJavaClassReference() {
        FileModel file = new FileModel(Path.of("/src/com/example/MyBean.java"));
        JavaClassReference ref = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.ANNOTATION,
                10, 1);
        ref.setSourceFile(file);

        ClassificationAction action = new ClassificationAction(
                "ejb-003", "EJB Bean", "Contains EJB annotations.",
                1, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref)));

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(1);

        ClassificationModel model = registry.findAll().get(0);
        assertThat(model.getSourceFile()).isSameAs(file);
    }

    @Test
    void shouldDeduplicateByFile() {
        FileModel file = new FileModel(Path.of("/src/com/example/MyBean.java"));

        JavaClassReference ref1 = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.ANNOTATION, 10, 1);
        ref1.setSourceFile(file);

        JavaClassReference ref2 = new JavaClassReference(
                "javax.ejb.EJB",
                JavaClassReference.ReferenceType.ANNOTATION, 25, 5);
        ref2.setSourceFile(file);

        ClassificationAction action = new ClassificationAction(
                "ejb-004", "EJB Bean", "Contains EJB annotations.",
                1, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref1, ref2)));

        // Only one classification for the same file
        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void shouldClassifyDistinctFilesSeparately() {
        FileModel file1 = new FileModel(Path.of("/src/A.java"));
        FileModel file2 = new FileModel(Path.of("/src/B.java"));

        JavaClassReference ref1 = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.ANNOTATION, 10, 1);
        ref1.setSourceFile(file1);

        JavaClassReference ref2 = new JavaClassReference(
                "javax.ejb.EJB",
                JavaClassReference.ReferenceType.ANNOTATION, 20, 5);
        ref2.setSourceFile(file2);

        ClassificationAction action = new ClassificationAction(
                "ejb-005", "EJB Bean", "Contains EJB annotations.",
                1, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref1, ref2)));

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void shouldDoNothingOnNoMatch() {
        ClassificationAction action = new ClassificationAction(
                "rule-x", "Title", "Desc", 1, null, List.of());

        action.perform(run, ConditionResult.noMatch());

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldDoNothingWithNullRun() {
        ClassificationAction action = new ClassificationAction(
                "rule-x", "Title", "Desc", 1, null, List.of());

        // null run + noMatch should not throw
        action.perform(null, ConditionResult.noMatch());
    }

    @Test
    void shouldSkipItemsWithNullSourceFile() {
        JavaClassReference ref = new JavaClassReference(
                "javax.ejb.Stateless",
                JavaClassReference.ReferenceType.IMPORT, 10, 0);
        // sourceFile is null

        ClassificationAction action = new ClassificationAction(
                "rule-y", "Title", "Desc", 1, null, List.of());

        action.perform(run, ConditionResult.match(List.of(ref)));

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldSkipUnknownItemTypes() {
        ClassificationAction action = new ClassificationAction(
                "rule-z", "Title", "Desc", 1, null, List.of());

        action.perform(run, ConditionResult.match(List.of("some-string")));

        ModelRegistry<ClassificationModel> registry = context.getOrCreateRegistry(ClassificationModel.class);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void shouldAttachMultipleLinks() {
        FileModel file = new FileModel(Path.of("/src/Test.java"));

        List<LinkModel> links = List.of(
                new LinkModel("Guide 1", "https://example.com/1"),
                new LinkModel("Guide 2", "https://example.com/2"));

        ClassificationAction action = new ClassificationAction(
                "rule-links", "Title", "Desc", 1, null, links);

        action.perform(run, ConditionResult.match(List.of(file)));

        ClassificationModel model = context.getOrCreateRegistry(ClassificationModel.class).findAll().get(0);
        assertThat(model.getLinks()).hasSize(2);
    }
}

package org.jboss.windup.rules.condition;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.java.model.JavaClassReference.ReferenceType;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link JavaClassCondition}.
 */
class JavaClassConditionTest {

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

    // ---- Glob pattern compilation tests ----

    @Test
    void globWithBracedStarMatchesSingleSegment() {
        Pattern p = JavaClassCondition.compileGlob("javax.ejb.{*}");
        assertThat(p.matcher("javax.ejb.Stateless").matches()).isTrue();
        assertThat(p.matcher("javax.ejb.Stateful").matches()).isTrue();
        // Should NOT match nested packages
        assertThat(p.matcher("javax.ejb.annotation.Local").matches()).isFalse();
        // Should NOT match empty segment
        assertThat(p.matcher("javax.ejb.").matches()).isFalse();
    }

    @Test
    void globWithBarStarMatchesAnything() {
        Pattern p = JavaClassCondition.compileGlob("javax.ejb.*");
        assertThat(p.matcher("javax.ejb.Stateless").matches()).isTrue();
        assertThat(p.matcher("javax.ejb.annotation.Local").matches()).isTrue();
        // Matches empty after dot too
        assertThat(p.matcher("javax.ejb.").matches()).isTrue();
    }

    @Test
    void globExactMatchWithNoBraces() {
        Pattern p = JavaClassCondition.compileGlob("javax.ejb.Stateless");
        assertThat(p.matcher("javax.ejb.Stateless").matches()).isTrue();
        assertThat(p.matcher("javax.ejb.Stateful").matches()).isFalse();
    }

    @Test
    void globWithMultipleBracedStars() {
        Pattern p = JavaClassCondition.compileGlob("{*}.{*}.{*}");
        assertThat(p.matcher("com.example.Foo").matches()).isTrue();
        assertThat(p.matcher("org.jboss.Bar").matches()).isTrue();
        assertThat(p.matcher("com.example.sub.Foo").matches()).isFalse();
    }

    @Test
    void globWithMixedStarAndBracedStar() {
        Pattern p = JavaClassCondition.compileGlob("javax.*.{*}");
        assertThat(p.matcher("javax.ejb.Stateless").matches()).isTrue();
        assertThat(p.matcher("javax.ejb.annotation.Local").matches()).isTrue();
        assertThat(p.matcher("javax..X").matches()).isTrue();
        // The {*} at the end requires at least one non-dot char
        assertThat(p.matcher("javax.ejb.").matches()).isFalse();
    }

    // ---- Condition evaluation tests ----

    @Test
    void matchesReferencesInContext() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);
        registerRef("javax.ejb.Stateful", ReferenceType.ANNOTATION, 20, 5);
        registerRef("java.util.List", ReferenceType.IMPORT, 1, 1);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void bracedStarDoesNotMatchNestedPackage() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);
        registerRef("javax.ejb.annotation.Local", ReferenceType.ANNOTATION, 15, 5);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
        JavaClassReference ref = (JavaClassReference) result.items().get(0);
        assertThat(ref.getQualifiedName()).isEqualTo("javax.ejb.Stateless");
    }

    @Test
    void bareStarMatchesNestedPackage() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);
        registerRef("javax.ejb.annotation.Local", ReferenceType.ANNOTATION, 15, 5);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.*", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void locationFilterMatchesCorrectType() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);
        registerRef("javax.ejb.Stateless", ReferenceType.IMPORT, 1, 1);
        registerRef("javax.ejb.Stateful", ReferenceType.ANNOTATION, 20, 5);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", "ANNOTATION");
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(2);
        for (Object item : result.items()) {
            assertThat(((JavaClassReference) item).getReferenceType()).isEqualTo(ReferenceType.ANNOTATION);
        }
    }

    @Test
    void locationFilterExcludesNonMatchingType() {
        registerRef("javax.ejb.Stateless", ReferenceType.IMPORT, 1, 1);
        registerRef("javax.ejb.Stateful", ReferenceType.IMPORT, 2, 1);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", "ANNOTATION");
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void emptyContextReturnsNoMatch() {
        // No references registered
        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void nullRunReturnsNoMatch() {
        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", null);
        ConditionResult result = condition.evaluate(null);

        assertThat(result.matched()).isFalse();
    }

    @Test
    void exactMatchWorks() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);
        registerRef("javax.ejb.Stateful", ReferenceType.ANNOTATION, 20, 5);

        JavaClassCondition condition = new JavaClassCondition("javax.ejb.Stateless", null);
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void methodCallLocationFilter() {
        registerRef("com.example.OldService.doWork()", ReferenceType.METHOD_CALL, 42, 10);
        registerRef("com.example.OldService", ReferenceType.IMPORT, 3, 1);

        JavaClassCondition condition = new JavaClassCondition("com.example.OldService*", "METHOD_CALL");
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
        JavaClassReference ref = (JavaClassReference) result.items().get(0);
        assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.METHOD_CALL);
    }

    @Test
    void invalidLocationThrowsException() {
        assertThatThrownBy(() -> new JavaClassCondition("javax.ejb.{*}", "BOGUS_LOCATION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown reference type location");
    }

    @Test
    void nullReferencesPatternThrowsException() {
        assertThatThrownBy(() -> new JavaClassCondition(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankReferencesPatternThrowsException() {
        assertThatThrownBy(() -> new JavaClassCondition("  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locationFilterIsCaseInsensitive() {
        registerRef("javax.ejb.Stateless", ReferenceType.ANNOTATION, 10, 5);

        // Location value is uppercased internally
        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", "annotation");
        ConditionResult result = condition.evaluate(run);

        assertThat(result.matched()).isTrue();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void gettersReturnCorrectValues() {
        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", "IMPORT");

        assertThat(condition.getReferencesGlob()).isEqualTo("javax.ejb.{*}");
        assertThat(condition.getLocationFilter()).isEqualTo(ReferenceType.IMPORT);
        assertThat(condition.getReferencesPattern()).isNotNull();
    }

    @Test
    void noLocationFilterGetterReturnsNull() {
        JavaClassCondition condition = new JavaClassCondition("javax.ejb.{*}", null);
        assertThat(condition.getLocationFilter()).isNull();
    }

    // ---- Helpers ----

    private void registerRef(String qualifiedName, ReferenceType type, int line, int col) {
        ModelRegistry<JavaClassReference> registry =
                context.getOrCreateRegistry(JavaClassReference.class);
        registry.register(new JavaClassReference(qualifiedName, type, line, col));
    }
}

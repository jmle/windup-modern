package org.jboss.windup.provider.buildtool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DependencyLabeler}: default open-source classification, internal
 * labeling when patterns don't match, and package exclusion support.
 */
class DependencyLabelerTest {

    @Test
    void shouldLabelAsOpenSourceByDefault() {
        DependencyLabeler labeler = new DependencyLabeler();
        BuildTool.ResolvedDependency dep = new BuildTool.ResolvedDependency(
                "org.springframework", "spring-core", "5.3.20", null, "compile", null, false);

        Map<String, String> labels = labeler.getLabels(dep);

        assertThat(labels).containsEntry("konveyor.io/language", "java");
        assertThat(labels).containsEntry("konveyor.io/dep-source", "open-source");
        assertThat(labels).doesNotContainKey("konveyor.io/exclude");
    }

    @Test
    void shouldLabelAsInternalWhenNotMatching() {
        List<Pattern> patterns = List.of(Pattern.compile("org\\.apache:.*"));
        DependencyLabeler labeler = new DependencyLabeler(patterns, Set.of());

        BuildTool.ResolvedDependency dep = new BuildTool.ResolvedDependency(
                "com.internal", "my-lib", "1.0", null, "compile", null, false);

        Map<String, String> labels = labeler.getLabels(dep);

        assertThat(labels).containsEntry("konveyor.io/dep-source", "internal");
    }

    @Test
    void shouldExcludeMatchingPackages() {
        DependencyLabeler labeler = new DependencyLabeler(List.of(), Set.of("com.excluded"));

        BuildTool.ResolvedDependency dep = new BuildTool.ResolvedDependency(
                "com.excluded.internal", "my-lib", "1.0", null, "compile", null, false);

        Map<String, String> labels = labeler.getLabels(dep);

        assertThat(labels).containsEntry("konveyor.io/exclude", "true");
    }
}

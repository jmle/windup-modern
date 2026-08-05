package org.jboss.windup.reporting.output;

import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.reporting.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ViolationOutputWriterTest {

    @Test
    void shouldGroupByRuleSetAndRule() {
        ViolationOutputWriter writer = new ViolationOutputWriter();

        InlineHintModel hint1 = createHint("ruleset-a.rule-1", "Title A1", "msg1", 1, "mandatory");
        InlineHintModel hint2 = createHint("ruleset-a.rule-1", "Title A1", "msg2", 5, "mandatory");
        InlineHintModel hint3 = createHint("ruleset-a.rule-2", "Title A2", "msg3", 10, "optional");
        InlineHintModel hint4 = createHint("ruleset-b.rule-1", "Title B1", "msg4", 3, "potential");

        List<RuleSetViolation> result = writer.buildOutput(List.of(hint1, hint2, hint3, hint4));

        assertThat(result).hasSize(2);

        RuleSetViolation rsA = result.get(0);
        assertThat(rsA.getName()).isEqualTo("ruleset-a");
        assertThat(rsA.getViolations()).hasSize(2);
        assertThat(rsA.getViolations()).containsKey("rule-1");
        assertThat(rsA.getViolations()).containsKey("rule-2");

        Violation violA1 = rsA.getViolations().get("rule-1");
        assertThat(violA1.getDescription()).isEqualTo("Title A1");
        assertThat(violA1.getCategory()).isEqualTo("mandatory");
        assertThat(violA1.getIncidents()).hasSize(2);
        assertThat(violA1.getEffort()).isEqualTo(1);

        RuleSetViolation rsB = result.get(1);
        assertThat(rsB.getName()).isEqualTo("ruleset-b");
        assertThat(rsB.getViolations()).hasSize(1);
    }

    @Test
    void shouldWriteOutputYaml(@TempDir Path tempDir) throws IOException {
        AnalysisContext context = new AnalysisContext();
        ModelRegistry<InlineHintModel> registry = context.getOrCreateRegistry(InlineHintModel.class);

        InlineHintModel hint = createHint("test-rules.rule-1", "Test Title", "Fix this", 5, "mandatory");
        hint.addLink(new LinkModel("Guide", "https://example.com"));
        registry.register(hint);

        ViolationOutputWriter writer = new ViolationOutputWriter();
        Path outputFile = writer.write(context, tempDir);

        assertThat(outputFile).exists();
        assertThat(outputFile.getFileName().toString()).isEqualTo("output.yaml");

        String content = Files.readString(outputFile);
        assertThat(content).contains("name: \"test-rules\"");
        assertThat(content).contains("rule-1:");
        assertThat(content).contains("description: \"Test Title\"");
        assertThat(content).contains("category: \"mandatory\"");
        assertThat(content).contains("message: \"Fix this\"");
        assertThat(content).contains("effort: 1");
        assertThat(content).contains("url: \"https://example.com\"");
        assertThat(content).contains("title: \"Guide\"");
    }

    @Test
    void shouldWriteEmptyArrayForNoHints(@TempDir Path tempDir) throws IOException {
        AnalysisContext context = new AnalysisContext();
        context.getOrCreateRegistry(InlineHintModel.class);

        ViolationOutputWriter writer = new ViolationOutputWriter();
        Path outputFile = writer.write(context, tempDir);

        assertThat(outputFile).exists();
        String content = Files.readString(outputFile);
        assertThat(content.trim()).isEqualTo("[]");
    }

    @Test
    void shouldIncludeFileUri() {
        ViolationOutputWriter writer = new ViolationOutputWriter();

        FileModel file = new FileModel(Path.of("/project/src/Main.java"));
        InlineHintModel hint = new InlineHintModel("Title", file, 42);
        hint.setHint("Fix it");
        hint.setRuleId("rs.rule-1");
        hint.setEffortPoints(1);
        hint.setCategory("mandatory");

        List<RuleSetViolation> result = writer.buildOutput(List.of(hint));

        Incident incident = result.get(0).getViolations().get("rule-1").getIncidents().get(0);
        assertThat(incident.getUri()).startsWith("file://");
        assertThat(incident.getUri()).contains("Main.java");
        assertThat(incident.getLineNumber()).isEqualTo(42);
        assertThat(incident.getMessage()).isEqualTo("Fix it");
    }

    @Test
    void shouldExtractRuleSetAndRuleId() {
        assertThat(ViolationOutputWriter.extractRuleSetName("jakarta-migration.rule-001"))
                .isEqualTo("jakarta-migration");
        assertThat(ViolationOutputWriter.extractRuleId("jakarta-migration.rule-001"))
                .isEqualTo("rule-001");

        assertThat(ViolationOutputWriter.extractRuleSetName("standalone-rule"))
                .isEqualTo("standalone-rule");
        assertThat(ViolationOutputWriter.extractRuleId("standalone-rule"))
                .isEqualTo("standalone-rule");

        assertThat(ViolationOutputWriter.extractRuleSetName(null))
                .isEqualTo("unknown");
    }

    private InlineHintModel createHint(String ruleId, String title, String message,
                                       int lineNumber, String category) {
        FileModel file = new FileModel(Path.of("/test/src/Example.java"));
        InlineHintModel hint = new InlineHintModel(title, file, lineNumber);
        hint.setHint(message);
        hint.setRuleId(ruleId);
        hint.setEffortPoints(1);
        hint.setCategory(category);
        hint.setEffort(EffortLevel.TRIVIAL);
        hint.setSeverity(Severity.COMPLEX);
        return hint;
    }
}

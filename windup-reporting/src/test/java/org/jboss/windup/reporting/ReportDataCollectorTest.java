package org.jboss.windup.reporting;

import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.Severity;
import org.jboss.windup.reporting.model.TechnologyTagLevel;
import org.jboss.windup.reporting.model.TechnologyTagModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDataCollectorTest {

    private AnalysisContext context;

    @BeforeEach
    void setUp() {
        context = new AnalysisContext();
    }

    @Test
    void emptySummaryWhenNoData() {
        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        assertThat(summary.getTotalFiles()).isZero();
        assertThat(summary.getTotalIncidents()).isZero();
        assertThat(summary.getTotalStoryPoints()).isZero();
        assertThat(summary.getIncidentsBySeverity()).containsEntry(Severity.INFORMATION, 0);
        assertThat(summary.getTechnologyTags()).isEmpty();
    }

    @Test
    void countsHintsAndClassifications() {
        FileModel file1 = new FileModel(Path.of("/src/Main.java"));
        FileModel file2 = new FileModel(Path.of("/src/Config.xml"));
        context.files().register(file1);
        context.files().register(file2);

        InlineHintModel hint1 = new InlineHintModel("Use CDI", file1, 10);
        hint1.setSeverity(Severity.COMPLEX);
        hint1.setEffort(EffortLevel.COMPLEX);
        hint1.setRuleId("rule-001");

        InlineHintModel hint2 = new InlineHintModel("Update API", file1, 25);
        hint2.setSeverity(Severity.COMPLEX);
        hint2.setEffort(EffortLevel.REDESIGN);
        hint2.setRuleId("rule-002");

        InlineHintModel hint3 = new InlineHintModel("Info hint", file2, 5);
        hint3.setSeverity(Severity.INFORMATION);
        hint3.setEffort(EffortLevel.TRIVIAL);
        hint3.setRuleId("rule-003");

        context.getOrCreateRegistry(InlineHintModel.class).register(hint1);
        context.getOrCreateRegistry(InlineHintModel.class).register(hint2);
        context.getOrCreateRegistry(InlineHintModel.class).register(hint3);

        ClassificationModel classif = new ClassificationModel("Spring Config", file2);
        classif.setSeverity(Severity.REDESIGN);
        classif.setEffort(EffortLevel.ARCHITECTURAL);
        classif.setRuleId("rule-004");
        context.getOrCreateRegistry(ClassificationModel.class).register(classif);

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        assertThat(summary.getTotalFiles()).isEqualTo(2);
        assertThat(summary.getTotalIncidents()).isEqualTo(4); // 3 hints + 1 classification
        // story points: COMPLEX(3) + REDESIGN(5) + TRIVIAL(1) + ARCHITECTURAL(7) = 16
        assertThat(summary.getTotalStoryPoints()).isEqualTo(16);
        assertThat(summary.getIncidentsBySeverity()).containsEntry(Severity.COMPLEX, 2);
        assertThat(summary.getIncidentsBySeverity()).containsEntry(Severity.INFORMATION, 1);
        assertThat(summary.getIncidentsBySeverity()).containsEntry(Severity.REDESIGN, 1);
    }

    @Test
    void collectsTechnologyTags() {
        TechnologyTagModel tag1 = new TechnologyTagModel("EJB", TechnologyTagLevel.IMPORTANT);
        TechnologyTagModel tag2 = new TechnologyTagModel("Hibernate", TechnologyTagLevel.INFORMATIONAL);
        context.getOrCreateRegistry(TechnologyTagModel.class).register(tag1);
        context.getOrCreateRegistry(TechnologyTagModel.class).register(tag2);

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        assertThat(summary.getTechnologyTags()).hasSize(2);
        assertThat(summary.getTechnologyTags()).extracting(TechnologyTagModel::name)
                .containsExactly("EJB", "Hibernate");
    }

    @Test
    void hintsWithNullSeverityCountAsInformation() {
        FileModel file = new FileModel(Path.of("/src/App.java"));
        context.files().register(file);

        InlineHintModel hint = new InlineHintModel("No severity", file, 1);
        // severity is null by default
        hint.setEffort(EffortLevel.TRIVIAL);
        context.getOrCreateRegistry(InlineHintModel.class).register(hint);

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        assertThat(summary.getTotalIncidents()).isEqualTo(1);
        assertThat(summary.getIncidentsBySeverity()).containsEntry(Severity.INFORMATION, 1);
    }

    @Test
    void hintsWithNullEffortContributeZeroPoints() {
        FileModel file = new FileModel(Path.of("/src/App.java"));
        context.files().register(file);

        InlineHintModel hint = new InlineHintModel("No effort", file, 1);
        hint.setSeverity(Severity.TRIVIAL);
        // effort is null by default
        context.getOrCreateRegistry(InlineHintModel.class).register(hint);

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        assertThat(summary.getTotalStoryPoints()).isZero();
    }

    @Test
    void summaryToStringContainsKeyFields() {
        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);
        String str = summary.toString();

        assertThat(str).contains("totalFiles=");
        assertThat(str).contains("totalIncidents=");
        assertThat(str).contains("totalStoryPoints=");
    }
}

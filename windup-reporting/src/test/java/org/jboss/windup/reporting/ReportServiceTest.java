package org.jboss.windup.reporting;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    @Test
    void generatesIndexReport() throws IOException {
        AnalysisRun run = createAnalysisRunWithData();

        reportService.generateIndexReport(run);

        Path indexFile = tempDir.resolve("output").resolve("index.html");
        assertThat(indexFile).exists();

        String content = Files.readString(indexFile);
        assertThat(content).contains("Analysis Summary");
        assertThat(content).contains("Files Analyzed");
        assertThat(content).contains("Total Incidents");
        assertThat(content).contains("Story Points");
        assertThat(content).contains("test-app");
    }

    @Test
    void indexReportContainsSeverityBreakdown() throws IOException {
        AnalysisRun run = createAnalysisRunWithData();

        reportService.generateIndexReport(run);

        Path indexFile = tempDir.resolve("output").resolve("index.html");
        String content = Files.readString(indexFile);
        assertThat(content).contains("Incidents by Severity");
        assertThat(content).contains("COMPLEX");
    }

    @Test
    void indexReportContainsTechnologyTags() throws IOException {
        AnalysisRun run = createAnalysisRunWithData();

        reportService.generateIndexReport(run);

        Path indexFile = tempDir.resolve("output").resolve("index.html");
        String content = Files.readString(indexFile);
        assertThat(content).contains("EJB");
        assertThat(content).contains("Detected Technologies");
    }

    @Test
    void generatesMigrationIssuesReport() throws IOException {
        AnalysisRun run = createAnalysisRunWithData();

        reportService.generateMigrationIssuesReport(run);

        Path issuesFile = tempDir.resolve("output").resolve("migration-issues.html");
        assertThat(issuesFile).exists();

        String content = Files.readString(issuesFile);
        assertThat(content).contains("Migration Issues");
        assertThat(content).contains("Replace EJB");
        assertThat(content).contains("ejb-to-cdi-001");
        assertThat(content).contains("Spring Config");
    }

    @Test
    void migrationIssuesReportGroupsBySeverity() throws IOException {
        AnalysisRun run = createAnalysisRunWithData();

        reportService.generateMigrationIssuesReport(run);

        Path issuesFile = tempDir.resolve("output").resolve("migration-issues.html");
        String content = Files.readString(issuesFile);
        assertThat(content).contains("COMPLEX");
        assertThat(content).contains("REDESIGN");
    }

    @Test
    void handlesEmptyData() throws IOException {
        AnalysisContext context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .build();
        AnalysisRun run = new AnalysisRun(context, config);
        Files.createDirectories(tempDir.resolve("input"));

        reportService.generateIndexReport(run);

        Path indexFile = tempDir.resolve("output").resolve("index.html");
        assertThat(indexFile).exists();

        String content = Files.readString(indexFile);
        assertThat(content).contains("Analysis Summary");
        // zero counts
        assertThat(content).contains(">0<");
    }

    private AnalysisRun createAnalysisRunWithData() throws IOException {
        AnalysisContext context = new AnalysisContext();
        Files.createDirectories(tempDir.resolve("input"));
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .build();
        AnalysisRun run = new AnalysisRun(context, config);

        // Register application name via input path
        // The input path's filename is used as the application name
        Path appInput = tempDir.resolve("test-app");
        Files.createDirectories(appInput);
        config = AnalysisConfiguration.builder()
                .inputPath(appInput)
                .outputDirectory(tempDir.resolve("output"))
                .build();
        run = new AnalysisRun(context, config);

        FileModel file = new FileModel(Path.of("/src/Main.java"));
        context.files().register(file);

        InlineHintModel hint = new InlineHintModel("Replace EJB", file, 42);
        hint.setHint("Use CDI instead of EJB");
        hint.setSeverity(Severity.COMPLEX);
        hint.setEffort(EffortLevel.COMPLEX);
        hint.setRuleId("ejb-to-cdi-001");
        context.getOrCreateRegistry(InlineHintModel.class).register(hint);

        ClassificationModel classif = new ClassificationModel("Spring Config", file);
        classif.setDescription("Spring XML configuration");
        classif.setSeverity(Severity.REDESIGN);
        classif.setEffort(EffortLevel.REDESIGN);
        classif.setRuleId("spring-001");
        context.getOrCreateRegistry(ClassificationModel.class).register(classif);

        TechnologyTagModel tag = new TechnologyTagModel("EJB", TechnologyTagLevel.IMPORTANT);
        context.getOrCreateRegistry(TechnologyTagModel.class).register(tag);

        return run;
    }
}

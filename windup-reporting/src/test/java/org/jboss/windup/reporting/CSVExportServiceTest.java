package org.jboss.windup.reporting;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CSVExportServiceTest {

    private CSVExportService csvExportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        csvExportService = new CSVExportService();
    }

    @Test
    void exportsHintsAndClassificationsToCSV() throws IOException {
        AnalysisContext context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .exportCSV(true)
                .build();
        AnalysisRun run = new AnalysisRun(context, config);

        FileModel file = new FileModel(Path.of("/src/Main.java"));
        context.files().register(file);

        InlineHintModel hint = new InlineHintModel("Replace EJB", file, 42);
        hint.setHint("Use CDI instead of EJB");
        hint.setSeverity(Severity.COMPLEX);
        hint.setEffort(EffortLevel.COMPLEX);
        hint.setRuleId("ejb-to-cdi-001");
        context.getOrCreateRegistry(InlineHintModel.class).register(hint);

        ClassificationModel classif = new ClassificationModel("Spring Config", file);
        classif.setDescription("Spring XML configuration file");
        classif.setSeverity(Severity.REDESIGN);
        classif.setEffort(EffortLevel.REDESIGN);
        classif.setRuleId("spring-config-001");
        context.getOrCreateRegistry(ClassificationModel.class).register(classif);

        // Create the input directory to satisfy the config
        Files.createDirectories(tempDir.resolve("input"));

        csvExportService.exportToCSV(run);

        Path csvFile = tempDir.resolve("output").resolve("AllIssues.csv");
        assertThat(csvFile).exists();

        List<String> lines = Files.readAllLines(csvFile);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3); // header + 1 hint + 1 classification

        // Verify header
        assertThat(lines.get(0)).contains("RuleId");
        assertThat(lines.get(0)).contains("Severity");
        assertThat(lines.get(0)).contains("Title");
        assertThat(lines.get(0)).contains("File");
        assertThat(lines.get(0)).contains("LineNumber");

        // Verify hint row
        assertThat(lines.get(1)).contains("ejb-to-cdi-001");
        assertThat(lines.get(1)).contains("COMPLEX");
        assertThat(lines.get(1)).contains("Replace EJB");
        assertThat(lines.get(1)).contains("42");

        // Verify classification row
        assertThat(lines.get(2)).contains("spring-config-001");
        assertThat(lines.get(2)).contains("REDESIGN");
        assertThat(lines.get(2)).contains("Spring Config");
    }

    @Test
    void doesNotExportWhenDisabled() throws IOException {
        AnalysisContext context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .exportCSV(false)
                .build();
        AnalysisRun run = new AnalysisRun(context, config);
        Files.createDirectories(tempDir.resolve("input"));

        csvExportService.exportToCSV(run);

        Path csvFile = tempDir.resolve("output").resolve("AllIssues.csv");
        assertThat(csvFile).doesNotExist();
    }

    @Test
    void handlesEmptyData() throws IOException {
        AnalysisContext context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .exportCSV(true)
                .build();
        AnalysisRun run = new AnalysisRun(context, config);
        Files.createDirectories(tempDir.resolve("input"));

        csvExportService.exportToCSV(run);

        Path csvFile = tempDir.resolve("output").resolve("AllIssues.csv");
        assertThat(csvFile).exists();

        List<String> lines = Files.readAllLines(csvFile);
        // Only header row
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("RuleId");
    }

    @Test
    void handlesNullFieldsGracefully() throws IOException {
        AnalysisContext context = new AnalysisContext();
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(tempDir.resolve("input"))
                .outputDirectory(tempDir.resolve("output"))
                .exportCSV(true)
                .build();
        AnalysisRun run = new AnalysisRun(context, config);
        Files.createDirectories(tempDir.resolve("input"));

        // Hint with minimal data (many nulls)
        InlineHintModel hint = new InlineHintModel();
        hint.setTitle("Minimal hint");
        context.getOrCreateRegistry(InlineHintModel.class).register(hint);

        csvExportService.exportToCSV(run);

        Path csvFile = tempDir.resolve("output").resolve("AllIssues.csv");
        assertThat(csvFile).exists();

        List<String> lines = Files.readAllLines(csvFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains("Minimal hint");
    }
}

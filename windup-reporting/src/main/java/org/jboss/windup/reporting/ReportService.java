package org.jboss.windup.reporting;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.ReportModel;
import org.jboss.windup.reporting.model.Severity;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for generating HTML reports using FreeMarker templates.
 * <p>
 * Processes {@link ReportModel} instances, loading templates from the classpath
 * and writing the rendered HTML to the configured output directory.
 */
@ApplicationScoped
public class ReportService {

    private static final Logger LOG = Logger.getLogger(ReportService.class.getName());

    private final Configuration freemarkerConfig;

    public ReportService() {
        freemarkerConfig = new Configuration(Configuration.VERSION_2_3_33);
        freemarkerConfig.setClassLoaderForTemplateLoading(
                getClass().getClassLoader(), "reports/templates");
        freemarkerConfig.setDefaultEncoding(StandardCharsets.UTF_8.name());
        freemarkerConfig.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        freemarkerConfig.setLogTemplateExceptions(false);
        freemarkerConfig.setWrapUncheckedExceptions(true);
    }

    /**
     * Generates the main index.html summary report.
     */
    public void generateIndexReport(AnalysisRun run) {
        AnalysisContext context = run.getContext();
        Path outputDir = run.getConfiguration().getOutputDirectory();

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        // Convert enum-keyed map to string-keyed map for FreeMarker compatibility
        Map<String, Integer> incidentsBySeverity = new LinkedHashMap<>();
        for (Map.Entry<Severity, Integer> entry : summary.getIncidentsBySeverity().entrySet()) {
            incidentsBySeverity.put(entry.getKey().name(), entry.getValue());
        }

        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("summary", summary);
        dataModel.put("incidentsBySeverity", incidentsBySeverity);
        dataModel.put("applicationName", resolveApplicationName(run));

        renderTemplate("index.ftl", dataModel, outputDir.resolve("index.html"));
    }

    /**
     * Generates the migration issues report, listing all hints and classifications
     * grouped by severity.
     */
    public void generateMigrationIssuesReport(AnalysisRun run) {
        AnalysisContext context = run.getContext();
        Path outputDir = run.getConfiguration().getOutputDirectory();

        List<InlineHintModel> hints = context.getOrCreateRegistry(InlineHintModel.class).findAll();
        List<ClassificationModel> classifications = context.getOrCreateRegistry(ClassificationModel.class).findAll();

        // Group hints by severity name (string keys for FreeMarker)
        Map<String, List<InlineHintModel>> hintsBySeverity = new LinkedHashMap<>();
        for (InlineHintModel hint : hints) {
            String sev = hint.getSeverity() != null ? hint.getSeverity().name() : Severity.INFORMATION.name();
            hintsBySeverity.computeIfAbsent(sev, k -> new ArrayList<>()).add(hint);
        }

        // Group classifications by severity name (string keys for FreeMarker)
        Map<String, List<ClassificationModel>> classificationsBySeverity = new LinkedHashMap<>();
        for (ClassificationModel c : classifications) {
            String sev = c.getSeverity() != null ? c.getSeverity().name() : Severity.INFORMATION.name();
            classificationsBySeverity.computeIfAbsent(sev, k -> new ArrayList<>()).add(c);
        }

        // Build severity names list for iteration
        List<String> severityNames = new ArrayList<>();
        for (Severity s : Severity.values()) {
            severityNames.add(s.name());
        }

        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("hintsBySeverity", hintsBySeverity);
        dataModel.put("classificationsBySeverity", classificationsBySeverity);
        dataModel.put("applicationName", resolveApplicationName(run));
        dataModel.put("severities", severityNames);

        renderTemplate("migration-issues.ftl", dataModel, outputDir.resolve("migration-issues.html"));
    }

    /**
     * Renders a single {@link ReportModel} to HTML using the template and
     * related resources defined on the model.
     */
    public void renderReport(ReportModel reportModel) {
        if (reportModel.getTemplatePath() == null || reportModel.getReportDirectory() == null
                || reportModel.getReportFilename() == null) {
            LOG.warning("Skipping report with incomplete configuration: " + reportModel);
            return;
        }

        Path outputPath = Path.of(reportModel.getReportDirectory()).resolve(reportModel.getReportFilename());
        Map<String, Object> dataModel = new HashMap<>(reportModel.getRelatedResources());
        dataModel.put("reportTitle", reportModel.getTitle());
        dataModel.put("reportDescription", reportModel.getDescription());

        renderTemplate(reportModel.getTemplatePath(), dataModel, outputPath);
    }

    private void renderTemplate(String templateName, Map<String, Object> dataModel, Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            Template template = freemarkerConfig.getTemplate(templateName);

            try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                template.process(dataModel, writer);
            }
            LOG.info("Generated report: " + outputPath);
        } catch (IOException | TemplateException e) {
            LOG.log(Level.SEVERE, "Failed to generate report: " + templateName, e);
        }
    }

    private String resolveApplicationName(AnalysisRun run) {
        var apps = run.getContext().applications().findAll();
        if (!apps.isEmpty()) {
            return apps.get(0).getName();
        }
        List<Path> inputs = run.getConfiguration().getInputPaths();
        if (!inputs.isEmpty()) {
            Path first = inputs.get(0);
            return first.getFileName() != null ? first.getFileName().toString() : first.toString();
        }
        return "Unknown Application";
    }
}

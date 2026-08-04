package org.jboss.windup.reporting;

import com.opencsv.CSVWriter;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.InlineHintModel;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for exporting analysis results to CSV format using OpenCSV.
 * <p>
 * The export only runs when {@code AnalysisConfiguration.isExportCSV()} is {@code true}.
 * Output format columns: RuleId, Severity, Title, Description, Effort, File, LineNumber.
 */
@ApplicationScoped
public class CSVExportService {

    private static final Logger LOG = Logger.getLogger(CSVExportService.class.getName());

    private static final String[] CSV_HEADER = {
            "RuleId", "Severity", "Title", "Description", "Effort", "File", "LineNumber"
    };

    /**
     * Exports all hints and classifications from the analysis run to a CSV file.
     * The file is written to the output directory as {@code AllIssues.csv}.
     *
     * @param run the current analysis run
     */
    public void exportToCSV(AnalysisRun run) {
        if (!run.getConfiguration().isExportCSV()) {
            LOG.fine("CSV export disabled; skipping.");
            return;
        }

        Path outputDir = run.getConfiguration().getOutputDirectory();
        Path csvFile = outputDir.resolve("AllIssues.csv");

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create output directory: " + outputDir, e);
            return;
        }

        AnalysisContext context = run.getContext();
        List<InlineHintModel> hints = context.getOrCreateRegistry(InlineHintModel.class).findAll();
        List<ClassificationModel> classifications = context.getOrCreateRegistry(ClassificationModel.class).findAll();

        try (Writer fileWriter = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8);
             CSVWriter csvWriter = new CSVWriter(fileWriter)) {

            csvWriter.writeNext(CSV_HEADER);

            for (InlineHintModel hint : hints) {
                csvWriter.writeNext(new String[]{
                        nullSafe(hint.getRuleId()),
                        hint.getSeverity() != null ? hint.getSeverity().name() : "",
                        nullSafe(hint.getTitle()),
                        nullSafe(hint.getHint()),
                        hint.getEffort() != null ? String.valueOf(hint.getEffort().getStoryPoints()) : "0",
                        hint.getSourceFile() != null ? hint.getSourceFile().getFilePath().toString() : "",
                        String.valueOf(hint.getLineNumber())
                });
            }

            for (ClassificationModel classification : classifications) {
                csvWriter.writeNext(new String[]{
                        nullSafe(classification.getRuleId()),
                        classification.getSeverity() != null ? classification.getSeverity().name() : "",
                        nullSafe(classification.getTitle()),
                        nullSafe(classification.getDescription()),
                        classification.getEffort() != null
                                ? String.valueOf(classification.getEffort().getStoryPoints()) : "0",
                        classification.getSourceFile() != null
                                ? classification.getSourceFile().getFilePath().toString() : "",
                        "" // classifications do not have a line number
                });
            }

            LOG.info("CSV export written to: " + csvFile);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write CSV export: " + csvFile, e);
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}

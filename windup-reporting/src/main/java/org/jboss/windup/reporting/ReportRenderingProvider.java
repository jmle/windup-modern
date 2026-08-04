package org.jboss.windup.reporting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.reporting.model.ReportModel;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that runs in {@link Phase#REPORT_RENDERING} to render
 * all {@link ReportModel} instances to HTML, and optionally export CSV data.
 * <p>
 * This provider executes after {@code ReportGenerationProvider} to ensure all
 * report models have been created before rendering begins.
 */
@ApplicationScoped
public class ReportRenderingProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(ReportRenderingProvider.class.getName());

    private static final RuleProviderMetadata METADATA = new RuleProviderMetadata(
            "ReportRenderingProvider",
            Phase.REPORT_RENDERING,
            Set.of(),
            Set.of(),
            Set.of(),
            List.of("ReportGenerationProvider"),
            List.of()
    );

    private final ReportService reportService;
    private final CSVExportService csvExportService;

    /**
     * No-arg constructor for CDI proxying.
     */
    @SuppressWarnings("unused")
    protected ReportRenderingProvider() {
        this.reportService = null;
        this.csvExportService = null;
    }

    @Inject
    public ReportRenderingProvider(ReportService reportService, CSVExportService csvExportService) {
        this.reportService = reportService;
        this.csvExportService = csvExportService;
    }

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "report-rendering-html",
                        run -> ConditionResult.match(List.of()),
                        this::renderHtmlReports,
                        new RuleMetadata(Phase.REPORT_RENDERING)
                ),
                new Rule(
                        "report-rendering-csv",
                        run -> ConditionResult.match(List.of()),
                        this::exportCsv,
                        new RuleMetadata(Phase.REPORT_RENDERING)
                )
        );
    }

    /**
     * Renders all registered report models to HTML using the ReportService.
     * Also generates the standard index and migration-issues reports directly.
     */
    void renderHtmlReports(AnalysisRun run, ConditionResult matched) {
        if (run.isCancelled()) return;

        LOG.info("Rendering HTML reports...");

        // Generate standard reports
        reportService.generateIndexReport(run);
        reportService.generateMigrationIssuesReport(run);

        // Render any additional report models registered by other providers
        List<ReportModel> reportModels = run.getContext()
                .getOrCreateRegistry(ReportModel.class).findAll();
        for (ReportModel reportModel : reportModels) {
            // Skip the ones we already generated directly above
            if ("index.html".equals(reportModel.getReportFilename())
                    || "migration-issues.html".equals(reportModel.getReportFilename())) {
                continue;
            }
            reportService.renderReport(reportModel);
        }

        LOG.info("HTML report rendering complete");
    }

    /**
     * Exports analysis results to CSV if enabled in the configuration.
     */
    void exportCsv(AnalysisRun run, ConditionResult matched) {
        if (run.isCancelled()) return;

        csvExportService.exportToCSV(run);
    }
}

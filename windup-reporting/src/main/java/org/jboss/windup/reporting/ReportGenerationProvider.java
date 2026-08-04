package org.jboss.windup.reporting;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.reporting.model.ReportModel;

import java.util.List;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that runs in {@link Phase#REPORT_GENERATION} to collect
 * analysis data and create {@link ReportModel} instances for later rendering.
 */
@ApplicationScoped
public class ReportGenerationProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(ReportGenerationProvider.class.getName());

    private static final RuleProviderMetadata METADATA =
            new RuleProviderMetadata("ReportGenerationProvider", Phase.REPORT_GENERATION);

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "report-generation-index",
                        run -> ConditionResult.match(List.of()),
                        this::createIndexReport,
                        new RuleMetadata(Phase.REPORT_GENERATION)
                ),
                new Rule(
                        "report-generation-migration-issues",
                        run -> ConditionResult.match(List.of()),
                        this::createMigrationIssuesReport,
                        new RuleMetadata(Phase.REPORT_GENERATION)
                )
        );
    }

    /**
     * Creates the index report model and registers it in the context.
     */
    void createIndexReport(AnalysisRun run, ConditionResult matched) {
        if (run.isCancelled()) return;

        AnalysisContext context = run.getContext();
        String outputDir = run.getConfiguration().getOutputDirectory().toString();

        ReportDataCollector.ReportSummary summary = ReportDataCollector.collectSummary(context);

        ReportModel indexReport = new ReportModel("Application Summary", "index.ftl");
        indexReport.setReportDirectory(outputDir);
        indexReport.setReportFilename("index.html");
        indexReport.setDescription("Summary of the analysis results");
        indexReport.putRelatedResource("summary", summary);
        indexReport.putRelatedResource("applicationName", resolveApplicationName(run));

        context.getOrCreateRegistry(ReportModel.class).register(indexReport);
        LOG.info("Created index report model");
    }

    /**
     * Creates the migration issues report model and registers it in the context.
     */
    void createMigrationIssuesReport(AnalysisRun run, ConditionResult matched) {
        if (run.isCancelled()) return;

        AnalysisContext context = run.getContext();
        String outputDir = run.getConfiguration().getOutputDirectory().toString();

        ReportModel issuesReport = new ReportModel("Migration Issues", "migration-issues.ftl");
        issuesReport.setReportDirectory(outputDir);
        issuesReport.setReportFilename("migration-issues.html");
        issuesReport.setDescription("All migration issues grouped by severity");

        context.getOrCreateRegistry(ReportModel.class).register(issuesReport);
        LOG.info("Created migration issues report model");
    }

    private String resolveApplicationName(AnalysisRun run) {
        var apps = run.getContext().applications().findAll();
        if (!apps.isEmpty()) {
            return apps.get(0).getName();
        }
        var inputs = run.getConfiguration().getInputPaths();
        if (!inputs.isEmpty()) {
            var first = inputs.get(0);
            return first.getFileName() != null ? first.getFileName().toString() : first.toString();
        }
        return "Unknown Application";
    }
}

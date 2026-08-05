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
import org.jboss.windup.reporting.output.ViolationOutputWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes analysis results as a Konveyor-format {@code output.yaml} file
 * in the configured output directory.
 */
@ApplicationScoped
public class ViolationOutputProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(ViolationOutputProvider.class.getName());

    private static final RuleProviderMetadata METADATA = new RuleProviderMetadata(
            "ViolationOutputProvider",
            Phase.REPORT_RENDERING,
            Set.of(),
            Set.of(),
            Set.of(),
            List.of(),
            List.of()
    );

    private final ViolationOutputWriter outputWriter;

    @SuppressWarnings("unused")
    protected ViolationOutputProvider() {
        this.outputWriter = null;
    }

    @Inject
    public ViolationOutputProvider(ViolationOutputWriter outputWriter) {
        this.outputWriter = outputWriter;
    }

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "violation-output-yaml",
                        run -> ConditionResult.match(List.of()),
                        this::writeOutput,
                        new RuleMetadata(Phase.REPORT_RENDERING)
                )
        );
    }

    void writeOutput(AnalysisRun run, ConditionResult matched) {
        if (run.isCancelled()) return;

        Path outputDir = run.getConfiguration().getOutputDirectory();
        try {
            Path outputFile = outputWriter.write(run.getContext(), outputDir);
            LOG.info("Violation output written to " + outputFile);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write violation output", e);
        }
    }
}

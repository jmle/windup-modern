package org.jboss.windup.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.windup.model.AnalysisContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class WindupProcessor {

    private static final Logger LOG = Logger.getLogger(WindupProcessor.class.getName());

    @Inject
    RuleEngine ruleEngine;

    @Inject
    DynamicRuleProviderRegistry dynamicRegistry;

    /**
     * Optional loader for YAML user rules. Injected when the windup-rules
     * module is on the classpath; null otherwise. We use the interface
     * {@link UserRuleLoader} to avoid a compile-time dependency on windup-rules.
     */
    @Inject
    jakarta.enterprise.inject.Instance<UserRuleLoader> userRuleLoader;

    public AnalysisContext execute(AnalysisConfiguration configuration) {
        LOG.info("Starting Windup analysis");
        LOG.info("Input paths: " + configuration.getInputPaths());
        LOG.info("Output directory: " + configuration.getOutputDirectory());

        try {
            Files.createDirectories(configuration.getOutputDirectory());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + configuration.getOutputDirectory(), e);
        }

        // Clear any dynamic providers from previous runs
        dynamicRegistry.clear();

        // Load user-provided rules before engine execution
        loadUserRules(configuration.getUserRulesPaths());

        AnalysisContext context = new AnalysisContext();
        AnalysisRun run = new AnalysisRun(context, configuration);

        ruleEngine.execute(run);

        LOG.info("Analysis finished. Files discovered: " + context.files().size());
        return context;
    }

    private void loadUserRules(List<Path> userRulesPaths) {
        if (userRulesPaths == null || userRulesPaths.isEmpty()) {
            return;
        }

        if (userRuleLoader.isUnsatisfied()) {
            LOG.warning("User rules paths configured but no UserRuleLoader implementation is available");
            return;
        }

        UserRuleLoader loader = userRuleLoader.get();
        for (Path rulesPath : userRulesPaths) {
            LOG.info("Loading user rules from: " + rulesPath);
            List<RuleProvider> loaded = loader.loadRules(rulesPath);
            if (!loaded.isEmpty()) {
                dynamicRegistry.registerAll(loaded);
                LOG.info("Loaded " + loaded.size() + " rule providers from: " + rulesPath);
            }
        }
    }
}

package org.jboss.windup.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RuleEngine {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    @Inject
    Instance<RuleProvider> providers;

    @Inject
    RuleProviderSorter sorter;

    @Inject
    DynamicRuleProviderRegistry dynamicRegistry;

    public void execute(AnalysisRun run) {
        List<RuleProvider> allProviders = new ArrayList<>();
        providers.forEach(allProviders::add);

        // Include dynamically registered providers (e.g. YAML rules loaded at startup)
        List<RuleProvider> dynamicProviders = dynamicRegistry.getProviders();
        if (!dynamicProviders.isEmpty()) {
            LOG.info("Including " + dynamicProviders.size() + " dynamic rule providers");
            allProviders.addAll(dynamicProviders);
        }

        List<RuleProvider> sorted = sorter.sort(allProviders);
        LOG.info("Executing " + sorted.size() + " rule providers");

        for (RuleProvider provider : sorted) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled, stopping execution");
                break;
            }

            LOG.fine("Running provider: " + provider.getMetadata().id()
                    + " [" + provider.getMetadata().phase() + "]");

            for (Rule rule : provider.getRules()) {
                if (run.isCancelled()) break;

                try {
                    ConditionResult result = rule.condition().evaluate(run);
                    if (result.matched()) {
                        rule.action().perform(run, result);
                    }
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Error executing rule: " + rule.id(), e);
                }
            }
        }
    }
}

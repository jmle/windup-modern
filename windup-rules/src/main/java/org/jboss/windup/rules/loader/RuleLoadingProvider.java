package org.jboss.windup.rules.loader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.UserRuleLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * CDI-managed implementation of {@link UserRuleLoader} that delegates to
 * {@link YamlRuleLoader} for loading YAML rule definitions from user-provided
 * directories.
 * <p>
 * This provider is discovered by {@link org.jboss.windup.engine.WindupProcessor}
 * via CDI and invoked before engine execution to load rules from the paths
 * configured in {@link org.jboss.windup.engine.AnalysisConfiguration#getUserRulesPaths()}.
 * The loaded providers are registered in
 * {@link org.jboss.windup.engine.DynamicRuleProviderRegistry} so that the
 * {@link org.jboss.windup.engine.RuleEngine} can include them alongside
 * CDI-discovered providers.
 */
@ApplicationScoped
public class RuleLoadingProvider implements UserRuleLoader {

    private static final Logger LOG = Logger.getLogger(RuleLoadingProvider.class.getName());

    @Inject
    YamlRuleLoader yamlRuleLoader;

    @Override
    public List<RuleProvider> loadRules(Path rulesDirectory) {
        LOG.info("Loading YAML rules from: " + rulesDirectory);
        List<RuleProvider> providers = yamlRuleLoader.loadRules(rulesDirectory);
        LOG.info("Loaded " + providers.size() + " rule providers from: " + rulesDirectory);
        return providers;
    }
}

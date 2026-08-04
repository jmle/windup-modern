package org.jboss.windup.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface for loading user-provided rule definitions from a
 * directory path. Implementations convert external rule formats (e.g. YAML
 * files) into {@link RuleProvider} instances that the engine can execute.
 * <p>
 * This interface lives in windup-core so that {@link WindupProcessor} can
 * reference it without a compile-time dependency on the rules module.
 * Concrete implementations (e.g. {@code RuleLoadingProvider} backed by
 * {@code YamlRuleLoader}) are discovered via CDI.
 */
public interface UserRuleLoader {

    /**
     * Loads rule providers from the given directory.
     *
     * @param rulesDirectory the root directory to scan for rule files
     * @return a list of loaded providers (never null; may be empty)
     */
    List<RuleProvider> loadRules(Path rulesDirectory);
}

package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.engine.RuleCondition;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.engine.Technology;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads Konveyor-format YAML rule files ({@code *.yaml}) from a directory
 * and converts them into {@link RuleProvider} instances.
 *
 * <p>Each file contains a bare YAML array of rules (no wrapper object).
 * Files named {@code ruleset.yaml} are skipped — they contain ruleset
 * metadata that is handled separately.</p>
 */
@ApplicationScoped
public class YamlRuleLoader {

    private static final Logger LOG = Logger.getLogger(YamlRuleLoader.class.getName());
    private static final String YAML_EXTENSION = ".yaml";
    private static final String RULESET_METADATA = "ruleset.yaml";
    private static final TypeReference<List<YamlRule>> RULE_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper yamlMapper;
    private final YamlRuleConditionFactory conditionFactory;
    private final YamlRuleActionFactory actionFactory;

    @Inject
    public YamlRuleLoader(YamlRuleConditionFactory conditionFactory,
                          YamlRuleActionFactory actionFactory) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.conditionFactory = conditionFactory;
        this.actionFactory = actionFactory;
    }

    @SuppressWarnings("unused")
    YamlRuleLoader() {
        this.yamlMapper = null;
        this.conditionFactory = null;
        this.actionFactory = null;
    }

    /**
     * Scans the given directory (recursively) for {@code *.yaml} files
     * (excluding {@code ruleset.yaml}), deserializes each as a list of
     * {@link YamlRule}, and converts them into {@link RuleProvider} instances.
     */
    public List<RuleProvider> loadRules(Path rulesDirectory) {
        if (rulesDirectory == null || !Files.isDirectory(rulesDirectory)) {
            LOG.warning(() -> "Rules directory does not exist or is not a directory: " + rulesDirectory);
            return List.of();
        }

        List<Path> ruleFiles = findRuleFiles(rulesDirectory);
        if (ruleFiles.isEmpty()) {
            LOG.fine(() -> "No .yaml rule files found in: " + rulesDirectory);
            return List.of();
        }

        List<RuleProvider> providers = new ArrayList<>();
        for (Path ruleFile : ruleFiles) {
            try {
                List<YamlRule> rules = parseRuleFile(ruleFile);
                if (rules != null && !rules.isEmpty()) {
                    RuleProvider provider = convertToRuleProvider(rules, ruleFile);
                    providers.add(provider);
                    LOG.info(() -> String.format("Loaded ruleset '%s' with %d rules from %s",
                            provider.getMetadata().id(),
                            provider.getRules().size(),
                            ruleFile.getFileName()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load rule file: " + ruleFile, e);
            }
        }

        return Collections.unmodifiableList(providers);
    }

    private List<Path> findRuleFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(YAML_EXTENSION))
                    .filter(p -> !p.getFileName().toString().equals(RULESET_METADATA))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan rules directory: " + directory, e);
        }
    }

    List<YamlRule> parseRuleFile(Path ruleFile) throws IOException {
        return yamlMapper.readValue(ruleFile.toFile(), RULE_LIST_TYPE);
    }

    RuleProvider convertToRuleProvider(List<YamlRule> yamlRules, Path sourceFile) {
        String rulesetId = deriveRulesetId(sourceFile);

        Set<Technology> sourceTech = new LinkedHashSet<>();
        Set<Technology> targetTech = new LinkedHashSet<>();
        for (YamlRule rule : yamlRules) {
            extractTechnologies(rule, sourceTech, targetTech);
        }

        RuleProviderMetadata metadata = new RuleProviderMetadata(
                rulesetId,
                Phase.MIGRATION_RULES,
                Set.of(),
                Collections.unmodifiableSet(sourceTech),
                Collections.unmodifiableSet(targetTech),
                List.of(),
                List.of()
        );

        List<Rule> rules = new ArrayList<>();
        for (YamlRule yamlRule : yamlRules) {
            rules.add(convertToRule(yamlRule, rulesetId));
        }

        List<Rule> immutableRules = List.copyOf(rules);
        return new RuleProvider() {
            @Override
            public RuleProviderMetadata getMetadata() {
                return metadata;
            }

            @Override
            public List<Rule> getRules() {
                return immutableRules;
            }

            @Override
            public String toString() {
                return "YamlRuleProvider{id='" + metadata.id() + "', rules=" + immutableRules.size() + "}";
            }
        };
    }

    private Rule convertToRule(YamlRule yamlRule, String rulesetId) {
        String ruleId = rulesetId + "." + yamlRule.getRuleID();

        RuleCondition condition = conditionFactory.createCondition(ruleId, yamlRule.getWhen());
        RuleAction action = actionFactory.createAction(ruleId, yamlRule);

        RuleMetadata ruleMetadata = new RuleMetadata(Phase.MIGRATION_RULES);

        return new Rule(ruleId, condition, action, ruleMetadata);
    }

    private String deriveRulesetId(Path sourceFile) {
        String filename = sourceFile.getFileName().toString();
        if (filename.endsWith(YAML_EXTENSION)) {
            return filename.substring(0, filename.length() - YAML_EXTENSION.length());
        }
        return filename;
    }

    private void extractTechnologies(YamlRule rule,
                                     Set<Technology> sourceTech,
                                     Set<Technology> targetTech) {
        if (rule.getLabels() == null) return;
        for (String label : rule.getLabels()) {
            if (label.startsWith("konveyor.io/source=")) {
                String value = label.substring("konveyor.io/source=".length());
                sourceTech.add(new Technology(value, null));
            } else if (label.startsWith("konveyor.io/target=")) {
                String value = label.substring("konveyor.io/target=".length());
                targetTech.add(new Technology(value, null));
            }
        }
    }
}

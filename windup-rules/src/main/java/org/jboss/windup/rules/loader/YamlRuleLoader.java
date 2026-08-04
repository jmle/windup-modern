package org.jboss.windup.rules.loader;

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
 * Loads YAML-based rule definitions ({@code .rules.yaml} files) from a directory
 * and converts them into {@link RuleProvider} instances that can be executed by
 * the rule engine.
 * <p>
 * This replaces the legacy XML (.windup.xml) rule format and OCPSoft Rewrite
 * engine with a simpler YAML format parsed by Jackson.
 */
@ApplicationScoped
public class YamlRuleLoader {

    private static final Logger LOG = Logger.getLogger(YamlRuleLoader.class.getName());
    private static final String RULES_FILE_SUFFIX = ".rules.yaml";

    private final ObjectMapper yamlMapper;
    private final YamlRuleConditionFactory conditionFactory;
    private final YamlRuleActionFactory actionFactory;

    /**
     * CDI constructor.
     */
    @Inject
    public YamlRuleLoader(YamlRuleConditionFactory conditionFactory,
                          YamlRuleActionFactory actionFactory) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.conditionFactory = conditionFactory;
        this.actionFactory = actionFactory;
    }

    /**
     * No-arg constructor for CDI proxy creation.
     */
    @SuppressWarnings("unused")
    YamlRuleLoader() {
        this.yamlMapper = null;
        this.conditionFactory = null;
        this.actionFactory = null;
    }

    /**
     * Scans the given directory (recursively) for {@code .rules.yaml} files,
     * deserializes each into a {@link YamlRuleDefinition}, and converts them
     * into {@link RuleProvider} instances.
     *
     * @param rulesDirectory the root directory to scan
     * @return a list of loaded rule providers (never null)
     * @throws UncheckedIOException if directory scanning or file reading fails
     */
    public List<RuleProvider> loadRules(Path rulesDirectory) {
        if (rulesDirectory == null || !Files.isDirectory(rulesDirectory)) {
            LOG.warning(() -> "Rules directory does not exist or is not a directory: " + rulesDirectory);
            return List.of();
        }

        List<Path> ruleFiles = findRuleFiles(rulesDirectory);
        if (ruleFiles.isEmpty()) {
            LOG.fine(() -> "No .rules.yaml files found in: " + rulesDirectory);
            return List.of();
        }

        List<RuleProvider> providers = new ArrayList<>();
        for (Path ruleFile : ruleFiles) {
            try {
                YamlRuleDefinition definition = parseRuleFile(ruleFile);
                if (definition != null && definition.getRules() != null) {
                    RuleProvider provider = convertToRuleProvider(definition.getRules(), ruleFile);
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

    /**
     * Finds all {@code .rules.yaml} files in the given directory, recursively.
     */
    private List<Path> findRuleFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(RULES_FILE_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan rules directory: " + directory, e);
        }
    }

    /**
     * Parses a single YAML rule file into a {@link YamlRuleDefinition}.
     */
    YamlRuleDefinition parseRuleFile(Path ruleFile) throws IOException {
        return yamlMapper.readValue(ruleFile.toFile(), YamlRuleDefinition.class);
    }

    /**
     * Converts a parsed {@link YamlRulesetHeader} into a {@link RuleProvider}.
     */
    RuleProvider convertToRuleProvider(YamlRulesetHeader header, Path sourceFile) {
        Phase phase = parsePhase(header.getPhase());

        Set<Technology> sourceTech = parseTechnology(header.getSourceTechnology());
        Set<Technology> targetTech = parseTechnology(header.getTargetTechnology());

        RuleProviderMetadata metadata = new RuleProviderMetadata(
                header.getId(),
                phase,
                Set.of(),
                sourceTech,
                targetTech,
                List.of(),
                List.of()
        );

        List<Rule> rules = new ArrayList<>();
        if (header.getRules() != null) {
            for (YamlRule yamlRule : header.getRules()) {
                rules.add(convertToRule(yamlRule, header.getId(), phase));
            }
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

    /**
     * Converts a single {@link YamlRule} into a {@link Rule}.
     */
    private Rule convertToRule(YamlRule yamlRule, String rulesetId, Phase defaultPhase) {
        String ruleId = rulesetId + "." + yamlRule.getId();

        RuleCondition condition = conditionFactory.createCondition(ruleId, yamlRule.getWhen());
        RuleAction action = actionFactory.createAction(ruleId, yamlRule.getPerform());

        RuleMetadata ruleMetadata = new RuleMetadata(defaultPhase);

        return new Rule(ruleId, condition, action, ruleMetadata);
    }

    /**
     * Parses a phase string to a {@link Phase} enum value.
     * Defaults to {@link Phase#MIGRATION_RULES} if null or unrecognised.
     */
    private Phase parsePhase(String phaseStr) {
        if (phaseStr == null || phaseStr.isBlank()) {
            return Phase.MIGRATION_RULES;
        }
        try {
            return Phase.valueOf(phaseStr);
        } catch (IllegalArgumentException e) {
            LOG.warning(() -> "Unknown phase '" + phaseStr + "', defaulting to MIGRATION_RULES");
            return Phase.MIGRATION_RULES;
        }
    }

    /**
     * Converts a YAML technology entry to a set containing one {@link Technology},
     * or an empty set if the entry is null.
     */
    private Set<Technology> parseTechnology(YamlRulesetHeader.YamlTechnology yamlTech) {
        if (yamlTech == null || yamlTech.getId() == null) {
            return Set.of();
        }
        Set<Technology> result = new LinkedHashSet<>();
        result.add(new Technology(yamlTech.getId(), yamlTech.getVersion()));
        return Collections.unmodifiableSet(result);
    }
}

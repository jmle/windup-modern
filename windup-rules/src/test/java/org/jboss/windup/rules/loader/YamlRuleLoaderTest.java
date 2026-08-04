package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.engine.Technology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the YAML rule loading pipeline:
 * YAML file -> Jackson deserialization -> RuleProvider conversion.
 */
class YamlRuleLoaderTest {

    private YamlRuleLoader loader;
    private YamlRuleConditionFactory conditionFactory;
    private YamlRuleActionFactory actionFactory;

    @BeforeEach
    void setUp() {
        conditionFactory = new YamlRuleConditionFactory();
        actionFactory = new YamlRuleActionFactory();
        loader = new YamlRuleLoader(conditionFactory, actionFactory);
    }

    // ---- Deserialization tests ----

    @Test
    void shouldDeserializeYamlRuleFile() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            assertThat(is).isNotNull();
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);

            assertThat(definition).isNotNull();
            assertThat(definition.getRules()).isNotNull();

            YamlRulesetHeader header = definition.getRules();
            assertThat(header.getId()).isEqualTo("test-ejb-migration");
            assertThat(header.getPhase()).isEqualTo("MIGRATION_RULES");

            // Source technology
            assertThat(header.getSourceTechnology()).isNotNull();
            assertThat(header.getSourceTechnology().getId()).isEqualTo("eap");
            assertThat(header.getSourceTechnology().getVersion()).isEqualTo("[6,7)");

            // Target technology
            assertThat(header.getTargetTechnology()).isNotNull();
            assertThat(header.getTargetTechnology().getId()).isEqualTo("eap");
            assertThat(header.getTargetTechnology().getVersion()).isEqualTo("[7,)");

            // Rules
            assertThat(header.getRules()).hasSize(3);
        }
    }

    @Test
    void shouldDeserializeJavaClassCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(0);

            assertThat(rule.getId()).isEqualTo("ejb-001");
            assertThat(rule.getWhen()).isNotNull();
            assertThat(rule.getWhen().getJavaClass()).isNotNull();
            assertThat(rule.getWhen().getJavaClass().getReferences()).isEqualTo("javax.ejb.{*}");
            assertThat(rule.getWhen().getJavaClass().getLocation()).isEqualTo("ANNOTATION");
            assertThat(rule.getWhen().getConditionType()).isEqualTo("java-class");
        }
    }

    @Test
    void shouldDeserializeXmlMatchesCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(1);

            assertThat(rule.getId()).isEqualTo("ejb-002");
            assertThat(rule.getWhen().getXmlMatches()).isNotNull();
            assertThat(rule.getWhen().getXmlMatches().getXpath()).isEqualTo("//jee:ejb-jar");
            assertThat(rule.getWhen().getXmlMatches().getNamespaces())
                    .containsEntry("jee", "http://xmlns.jcp.org/xml/ns/javaee");
            assertThat(rule.getWhen().getConditionType()).isEqualTo("xml-matches");
        }
    }

    @Test
    void shouldDeserializeFileContentCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(2);

            assertThat(rule.getId()).isEqualTo("ejb-003");
            assertThat(rule.getWhen().getFileContent()).isNotNull();
            assertThat(rule.getWhen().getFileContent().getPattern()).isEqualTo("javax\\.ejb\\.");
            assertThat(rule.getWhen().getFileContent().getFilename()).isEqualTo("*.java");
            assertThat(rule.getWhen().getConditionType()).isEqualTo("file-content");
        }
    }

    @Test
    void shouldDeserializeHintAction() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(0);

            assertThat(rule.getPerform()).isNotNull();
            assertThat(rule.getPerform().getHint()).isNotNull();
            assertThat(rule.getPerform().getActionType()).isEqualTo("hint");

            YamlAction.HintAction hint = rule.getPerform().getHint();
            assertThat(hint.getTitle()).isEqualTo("EJB annotation must be migrated");
            assertThat(hint.getMessage()).isEqualTo("Replace javax.ejb with jakarta.ejb");
            assertThat(hint.getEffort()).isEqualTo(1);
            assertThat(hint.getCategory()).isEqualTo("MANDATORY");
            assertThat(hint.getLink()).isNotNull();
            assertThat(hint.getLink().getTitle()).isEqualTo("Migration Guide");
            assertThat(hint.getLink().getUrl()).isEqualTo("https://example.com/ejb-migration");
        }
    }

    @Test
    void shouldDeserializeClassificationAction() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(1);

            assertThat(rule.getPerform().getClassification()).isNotNull();
            assertThat(rule.getPerform().getActionType()).isEqualTo("classification");

            YamlAction.ClassificationAction classification = rule.getPerform().getClassification();
            assertThat(classification.getTitle()).isEqualTo("EJB Deployment Descriptor");
            assertThat(classification.getDescription())
                    .isEqualTo("This is an EJB deployment descriptor that needs updating.");
            assertThat(classification.getEffort()).isEqualTo(3);
        }
    }

    @Test
    void shouldDeserializeTechnologyTagAction() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            YamlRuleDefinition definition = mapper.readValue(is, YamlRuleDefinition.class);
            YamlRule rule = definition.getRules().getRules().get(2);

            assertThat(rule.getPerform().getTechnologyTag()).isNotNull();
            assertThat(rule.getPerform().getActionType()).isEqualTo("technology-tag");

            YamlAction.TechnologyTagAction techTag = rule.getPerform().getTechnologyTag();
            assertThat(techTag.getTag()).isEqualTo("EJB");
            assertThat(techTag.getLevel()).isEqualTo("IMPORTANT");
        }
    }

    // ---- Loader integration tests ----

    @Test
    void shouldLoadRulesFromDirectory(@TempDir Path tempDir) throws IOException {
        // Copy test YAML to temp directory
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);

        assertThat(providers).hasSize(1);

        RuleProvider provider = providers.get(0);
        RuleProviderMetadata metadata = provider.getMetadata();

        assertThat(metadata.id()).isEqualTo("test-ejb-migration");
        assertThat(metadata.phase()).isEqualTo(Phase.MIGRATION_RULES);

        // Source technology
        assertThat(metadata.sourceTechnologies()).hasSize(1);
        Technology sourceTech = metadata.sourceTechnologies().iterator().next();
        assertThat(sourceTech.id()).isEqualTo("eap");
        assertThat(sourceTech.versionRange()).isEqualTo("[6,7)");

        // Target technology
        assertThat(metadata.targetTechnologies()).hasSize(1);
        Technology targetTech = metadata.targetTechnologies().iterator().next();
        assertThat(targetTech.id()).isEqualTo("eap");
        assertThat(targetTech.versionRange()).isEqualTo("[7,)");

        // Rules
        assertThat(provider.getRules()).hasSize(3);
    }

    @Test
    void shouldCreateRulesWithCorrectIds(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        List<Rule> rules = providers.get(0).getRules();

        assertThat(rules.get(0).id()).isEqualTo("test-ejb-migration.ejb-001");
        assertThat(rules.get(1).id()).isEqualTo("test-ejb-migration.ejb-002");
        assertThat(rules.get(2).id()).isEqualTo("test-ejb-migration.ejb-003");
    }

    @Test
    void shouldAssignCorrectPhaseToRules(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        for (Rule rule : providers.get(0).getRules()) {
            assertThat(rule.metadata().phase()).isEqualTo(Phase.MIGRATION_RULES);
        }
    }

    @Test
    void stubConditionsShouldReturnNoMatch(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        for (Rule rule : providers.get(0).getRules()) {
            ConditionResult result = rule.condition().evaluate(null);
            assertThat(result.matched()).isFalse();
        }
    }

    @Test
    void shouldHandleEmptyDirectory(@TempDir Path tempDir) {
        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).isEmpty();
    }

    @Test
    void shouldHandleNullDirectory() {
        List<RuleProvider> providers = loader.loadRules(null);
        assertThat(providers).isEmpty();
    }

    @Test
    void shouldHandleNonExistentDirectory() {
        List<RuleProvider> providers = loader.loadRules(Path.of("/nonexistent/path"));
        assertThat(providers).isEmpty();
    }

    @Test
    void shouldLoadMultipleRuleFiles(@TempDir Path tempDir) throws IOException {
        // Write first rule file
        String yaml1 = """
                rules:
                  id: "ruleset-a"
                  rules:
                    - id: "rule-a1"
                      when:
                        java-class:
                          references: "com.example.A"
                      perform:
                        hint:
                          title: "Migrate A"
                          message: "Update class A"
                          effort: 1
                """;
        Files.writeString(tempDir.resolve("a.rules.yaml"), yaml1);

        // Write second rule file
        String yaml2 = """
                rules:
                  id: "ruleset-b"
                  rules:
                    - id: "rule-b1"
                      when:
                        file-content:
                          pattern: "deprecated-api"
                      perform:
                        classification:
                          title: "Deprecated API"
                          description: "Uses deprecated API calls"
                          effort: 5
                """;
        Files.writeString(tempDir.resolve("b.rules.yaml"), yaml2);

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(2);

        // Providers are sorted by filename, so 'a' comes first
        assertThat(providers.get(0).getMetadata().id()).isEqualTo("ruleset-a");
        assertThat(providers.get(0).getRules()).hasSize(1);

        assertThat(providers.get(1).getMetadata().id()).isEqualTo("ruleset-b");
        assertThat(providers.get(1).getRules()).hasSize(1);
    }

    @Test
    void shouldIgnoreNonYamlFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "This is not a rule file");
        Files.writeString(tempDir.resolve("data.yaml"), "not-a-rule: true");

        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, tempDir.resolve("real.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getMetadata().id()).isEqualTo("test-ejb-migration");
    }

    @Test
    void shouldDefaultPhaseToMigrationRules(@TempDir Path tempDir) throws IOException {
        String yaml = """
                rules:
                  id: "no-phase-ruleset"
                  rules:
                    - id: "rule-001"
                      when:
                        java-class:
                          references: "com.example.Foo"
                      perform:
                        hint:
                          title: "Migrate Foo"
                          message: "Update Foo"
                          effort: 1
                """;
        Files.writeString(tempDir.resolve("no-phase.rules.yaml"), yaml);

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getMetadata().phase()).isEqualTo(Phase.MIGRATION_RULES);
    }

    @Test
    void shouldLoadRulesFromSubdirectories(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("sub/nested");
        Files.createDirectories(subDir);

        try (InputStream is = getClass().getResourceAsStream("/test-rules.rules.yaml")) {
            Files.copy(is, subDir.resolve("nested.rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
    }

    // ---- Condition factory tests ----

    @Test
    void conditionFactoryShouldCreateJavaClassCondition() {
        YamlCondition condition = new YamlCondition();
        YamlCondition.JavaClassCondition jcc = new YamlCondition.JavaClassCondition();
        jcc.setReferences("javax.ejb.{*}");
        jcc.setLocation("ANNOTATION");
        condition.setJavaClass(jcc);

        var ruleCondition = conditionFactory.createCondition("test-rule", condition);
        assertThat(ruleCondition).isNotNull();

        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionFactoryShouldCreateXmlMatchesCondition() {
        YamlCondition condition = new YamlCondition();
        YamlCondition.XmlMatchesCondition xmc = new YamlCondition.XmlMatchesCondition();
        xmc.setXpath("//jee:ejb-jar");
        condition.setXmlMatches(xmc);

        var ruleCondition = conditionFactory.createCondition("test-rule", condition);
        assertThat(ruleCondition).isNotNull();

        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionFactoryShouldCreateFileContentCondition() {
        YamlCondition condition = new YamlCondition();
        YamlCondition.FileContentCondition fcc = new YamlCondition.FileContentCondition();
        fcc.setPattern("javax\\.ejb\\.");
        fcc.setFilename("*.java");
        condition.setFileContent(fcc);

        var ruleCondition = conditionFactory.createCondition("test-rule", condition);
        assertThat(ruleCondition).isNotNull();

        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionFactoryShouldReturnNoMatchForNullCondition() {
        var ruleCondition = conditionFactory.createCondition("test-rule", null);
        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    // ---- Action factory tests ----

    @Test
    void actionFactoryShouldCreateHintAction() {
        YamlAction action = new YamlAction();
        YamlAction.HintAction hint = new YamlAction.HintAction();
        hint.setTitle("Test Hint");
        hint.setMessage("Test message");
        hint.setEffort(1);
        hint.setCategory("MANDATORY");
        action.setHint(hint);

        var ruleAction = actionFactory.createAction("test-rule", action);
        assertThat(ruleAction).isNotNull();
        // Should not throw when performed with a no-match result
        ruleAction.perform(null, ConditionResult.noMatch());
    }

    @Test
    void actionFactoryShouldCreateClassificationAction() {
        YamlAction action = new YamlAction();
        YamlAction.ClassificationAction classification = new YamlAction.ClassificationAction();
        classification.setTitle("Test Classification");
        classification.setDescription("Test description");
        classification.setEffort(3);
        action.setClassification(classification);

        var ruleAction = actionFactory.createAction("test-rule", action);
        assertThat(ruleAction).isNotNull();
        ruleAction.perform(null, ConditionResult.noMatch());
    }

    @Test
    void actionFactoryShouldCreateTechnologyTagAction() {
        YamlAction action = new YamlAction();
        YamlAction.TechnologyTagAction techTag = new YamlAction.TechnologyTagAction();
        techTag.setTag("EJB");
        techTag.setLevel("IMPORTANT");
        action.setTechnologyTag(techTag);

        var ruleAction = actionFactory.createAction("test-rule", action);
        assertThat(ruleAction).isNotNull();
        ruleAction.perform(null, ConditionResult.noMatch());
    }

    @Test
    void actionFactoryShouldReturnNoopForNullAction() {
        var ruleAction = actionFactory.createAction("test-rule", null);
        assertThat(ruleAction).isNotNull();
        // Should not throw
        ruleAction.perform(null, ConditionResult.noMatch());
    }
}

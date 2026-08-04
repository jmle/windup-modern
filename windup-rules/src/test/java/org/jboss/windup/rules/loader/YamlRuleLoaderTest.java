package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            assertThat(is).isNotNull();
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});

            assertThat(rules).hasSize(3);

            YamlRule first = rules.get(0);
            assertThat(first.getRuleID()).isEqualTo("ejb-001");
            assertThat(first.getDescription()).isEqualTo("EJB annotation must be migrated");
            assertThat(first.getMessage()).isEqualTo("Replace javax.ejb with jakarta.ejb");
            assertThat(first.getEffort()).isEqualTo(1);
            assertThat(first.getCategory()).isEqualTo("MANDATORY");

            assertThat(first.getLabels())
                    .contains("konveyor.io/source=eap6", "konveyor.io/target=eap7");
        }
    }

    @Test
    void shouldDeserializeJavaReferencedCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});
            YamlRule rule = rules.get(0);

            assertThat(rule.getRuleID()).isEqualTo("ejb-001");
            assertThat(rule.getWhen()).isNotNull();
            assertThat(rule.getWhen()).containsKey("java.referenced");

            @SuppressWarnings("unchecked")
            Map<String, Object> javaRef = (Map<String, Object>) rule.getWhen().get("java.referenced");
            assertThat(javaRef.get("pattern")).isEqualTo("javax.ejb.{*}");
            assertThat(javaRef.get("location")).isEqualTo("ANNOTATION");
        }
    }

    @Test
    void shouldDeserializeXmlCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});
            YamlRule rule = rules.get(1);

            assertThat(rule.getRuleID()).isEqualTo("ejb-002");
            assertThat(rule.getWhen()).containsKey("builtin.xml");

            @SuppressWarnings("unchecked")
            Map<String, Object> xmlCond = (Map<String, Object>) rule.getWhen().get("builtin.xml");
            assertThat(xmlCond.get("xpath")).isEqualTo("//jee:ejb-jar");

            @SuppressWarnings("unchecked")
            Map<String, String> namespaces = (Map<String, String>) xmlCond.get("namespaces");
            assertThat(namespaces).containsEntry("jee", "http://xmlns.jcp.org/xml/ns/javaee");
        }
    }

    @Test
    void shouldDeserializeFileContentCondition() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});
            YamlRule rule = rules.get(2);

            assertThat(rule.getRuleID()).isEqualTo("ejb-003");
            assertThat(rule.getWhen()).containsKey("builtin.filecontent");

            @SuppressWarnings("unchecked")
            Map<String, Object> fileCond = (Map<String, Object>) rule.getWhen().get("builtin.filecontent");
            assertThat(fileCond.get("pattern")).isEqualTo("javax\\.ejb\\.");
            assertThat(fileCond.get("filePattern")).isEqualTo("*.java");
        }
    }

    @Test
    void shouldDeserializeLinks() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});
            YamlRule rule = rules.get(0);

            assertThat(rule.getLinks()).hasSize(1);
            assertThat(rule.getLinks().get(0).getTitle()).isEqualTo("Migration Guide");
            assertThat(rule.getLinks().get(0).getUrl()).isEqualTo("https://example.com/ejb-migration");
        }
    }

    @Test
    void shouldDeserializeTags() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            List<YamlRule> rules = mapper.readValue(is, new TypeReference<List<YamlRule>>() {});
            YamlRule rule = rules.get(2);

            assertThat(rule.getTag()).containsExactly("EJB");
        }
    }

    // ---- Loader integration tests ----

    @Test
    void shouldLoadRulesFromDirectory(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);

        assertThat(providers).hasSize(1);

        RuleProvider provider = providers.get(0);
        RuleProviderMetadata metadata = provider.getMetadata();

        assertThat(metadata.id()).isEqualTo("test-rules");
        assertThat(metadata.phase()).isEqualTo(Phase.MIGRATION_RULES);

        // Technologies extracted from labels
        assertThat(metadata.sourceTechnologies()).hasSize(1);
        Technology sourceTech = metadata.sourceTechnologies().iterator().next();
        assertThat(sourceTech.id()).isEqualTo("eap6");

        assertThat(metadata.targetTechnologies()).hasSize(1);
        Technology targetTech = metadata.targetTechnologies().iterator().next();
        assertThat(targetTech.id()).isEqualTo("eap7");

        assertThat(provider.getRules()).hasSize(3);
    }

    @Test
    void shouldCreateRulesWithCorrectIds(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        List<Rule> rules = providers.get(0).getRules();

        assertThat(rules.get(0).id()).isEqualTo("test-rules.ejb-001");
        assertThat(rules.get(1).id()).isEqualTo("test-rules.ejb-002");
        assertThat(rules.get(2).id()).isEqualTo("test-rules.ejb-003");
    }

    @Test
    void shouldAssignMigrationRulesPhase(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        for (Rule rule : providers.get(0).getRules()) {
            assertThat(rule.metadata().phase()).isEqualTo(Phase.MIGRATION_RULES);
        }
    }

    @Test
    void stubConditionsShouldReturnNoMatch(@TempDir Path tempDir) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("test-rules.yaml"));
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
        String yaml1 = """
                - ruleID: rule-a1
                  description: "Migrate A"
                  message: "Update class A"
                  effort: 1
                  when:
                    java.referenced:
                      pattern: "com.example.A"
                """;
        Files.writeString(tempDir.resolve("a.yaml"), yaml1);

        String yaml2 = """
                - ruleID: rule-b1
                  description: "Deprecated API"
                  message: "Uses deprecated API calls"
                  effort: 5
                  when:
                    builtin.filecontent:
                      pattern: "deprecated-api"
                """;
        Files.writeString(tempDir.resolve("b.yaml"), yaml2);

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(2);

        assertThat(providers.get(0).getMetadata().id()).isEqualTo("a");
        assertThat(providers.get(0).getRules()).hasSize(1);

        assertThat(providers.get(1).getMetadata().id()).isEqualTo("b");
        assertThat(providers.get(1).getRules()).hasSize(1);
    }

    @Test
    void shouldIgnoreNonYamlFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "This is not a rule file");

        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("real.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getMetadata().id()).isEqualTo("real");
    }

    @Test
    void shouldSkipRulesetYaml(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("ruleset.yaml"), "name: test-ruleset\n");

        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, tempDir.resolve("actual.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getMetadata().id()).isEqualTo("actual");
    }

    @Test
    void shouldLoadRulesFromSubdirectories(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("sub/nested");
        Files.createDirectories(subDir);

        try (InputStream is = getClass().getResourceAsStream("/test-rules.yaml")) {
            Files.copy(is, subDir.resolve("nested.yaml"));
        }

        List<RuleProvider> providers = loader.loadRules(tempDir);
        assertThat(providers).hasSize(1);
    }

    // ---- Condition factory tests ----

    @Test
    void conditionFactoryShouldCreateJavaReferencedCondition() {
        Map<String, Object> when = Map.of(
                "java.referenced", Map.of(
                        "pattern", "javax.ejb.{*}",
                        "location", "ANNOTATION"));

        var ruleCondition = conditionFactory.createCondition("test-rule", when);
        assertThat(ruleCondition).isNotNull();

        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionFactoryShouldCreateXmlCondition() {
        Map<String, Object> when = Map.of(
                "builtin.xml", Map.of("xpath", "//jee:ejb-jar"));

        var ruleCondition = conditionFactory.createCondition("test-rule", when);
        assertThat(ruleCondition).isNotNull();

        ConditionResult result = ruleCondition.evaluate(null);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void conditionFactoryShouldCreateFileContentCondition() {
        Map<String, Object> when = Map.of(
                "builtin.filecontent", Map.of(
                        "pattern", "javax\\.ejb\\.",
                        "filePattern", "*.java"));

        var ruleCondition = conditionFactory.createCondition("test-rule", when);
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
        YamlRule rule = new YamlRule();
        rule.setDescription("Test Hint");
        rule.setMessage("Test message");
        rule.setEffort(1);
        rule.setCategory("MANDATORY");

        var ruleAction = actionFactory.createAction("test-rule", rule);
        assertThat(ruleAction).isNotNull();
        ruleAction.perform(null, ConditionResult.noMatch());
    }

    @Test
    void actionFactoryShouldReturnNoopForNullRule() {
        var ruleAction = actionFactory.createAction("test-rule", null);
        assertThat(ruleAction).isNotNull();
        ruleAction.perform(null, ConditionResult.noMatch());
    }

    @Test
    void actionFactoryShouldReturnNoopForEmptyRule() {
        YamlRule rule = new YamlRule();
        var ruleAction = actionFactory.createAction("test-rule", rule);
        assertThat(ruleAction).isNotNull();
        ruleAction.perform(null, ConditionResult.noMatch());
    }
}

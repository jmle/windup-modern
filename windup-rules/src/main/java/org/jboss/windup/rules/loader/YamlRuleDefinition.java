package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Top-level Jackson-deserializable POJO representing a YAML rule file.
 * <p>
 * A {@code .rules.yaml} file has a single top-level key {@code rules}
 * whose value is a {@link YamlRulesetHeader}.
 *
 * <pre>
 * rules:
 *   id: "my-ruleset"
 *   phase: MIGRATION_RULES
 *   source-technology: {id: "eap", version: "[6,7)"}
 *   target-technology: {id: "eap", version: "[7,)"}
 *   rules:
 *     - id: "my-rule-001"
 *       when:
 *         java-class:
 *           references: "javax.ejb.{*}"
 *           location: ANNOTATION
 *       perform:
 *         hint:
 *           title: "EJB annotation"
 *           message: "Replace javax.ejb with jakarta.ejb"
 *           effort: 1
 *           category: MANDATORY
 *           link:
 *             title: "Migration Guide"
 *             url: "https://example.com"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRuleDefinition {

    private YamlRulesetHeader rules;

    public YamlRuleDefinition() {
    }

    public YamlRulesetHeader getRules() {
        return rules;
    }

    public void setRules(YamlRulesetHeader rules) {
        this.rules = rules;
    }
}

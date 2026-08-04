package org.jboss.windup.rules.loader;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleCondition;
import org.jboss.windup.rules.condition.FileContentCondition;
import org.jboss.windup.rules.condition.JavaClassCondition;
import org.jboss.windup.rules.condition.XmlXPathCondition;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Converts Konveyor-format {@code when} blocks (parsed as {@code Map<String, Object>})
 * into {@link RuleCondition} instances.
 *
 * <p>Supported condition keys:</p>
 * <ul>
 *   <li>{@code java.referenced} — matches Java class references</li>
 *   <li>{@code builtin.xml} — matches XML content via XPath</li>
 *   <li>{@code builtin.filecontent} — matches file content via regex</li>
 * </ul>
 */
@ApplicationScoped
public class YamlRuleConditionFactory {

    private static final Logger LOG = Logger.getLogger(YamlRuleConditionFactory.class.getName());

    public RuleCondition createCondition(String ruleId, Map<String, Object> when) {
        if (when == null || when.isEmpty()) {
            return run -> ConditionResult.noMatch();
        }

        if (when.containsKey("java.referenced")) {
            return createJavaReferencedCondition(ruleId, asMap(when.get("java.referenced")));
        }
        if (when.containsKey("builtin.xml")) {
            return createXmlCondition(ruleId, asMap(when.get("builtin.xml")));
        }
        if (when.containsKey("builtin.filecontent")) {
            return createFileContentCondition(ruleId, asMap(when.get("builtin.filecontent")));
        }

        throw new IllegalArgumentException(
                "Rule '" + ruleId + "' has a 'when' block with no recognised condition type. " +
                "Supported types: java.referenced, builtin.xml, builtin.filecontent");
    }

    private RuleCondition createJavaReferencedCondition(String ruleId, Map<String, Object> params) {
        String pattern = getString(params, "pattern");
        String location = getString(params, "location");

        LOG.fine(() -> String.format(
                "[%s] Creating java.referenced condition: pattern='%s', location='%s'",
                ruleId, pattern, location));

        return new JavaClassCondition(pattern, location);
    }

    private RuleCondition createXmlCondition(String ruleId, Map<String, Object> params) {
        String xpath = getString(params, "xpath");

        LOG.fine(() -> String.format(
                "[%s] Creating builtin.xml condition: xpath='%s'",
                ruleId, xpath));

        @SuppressWarnings("unchecked")
        Map<String, String> namespaces = (Map<String, String>) params.get("namespaces");

        return new XmlXPathCondition(xpath, namespaces);
    }

    private RuleCondition createFileContentCondition(String ruleId, Map<String, Object> params) {
        String pattern = getString(params, "pattern");
        String filePattern = getString(params, "filePattern");

        LOG.fine(() -> String.format(
                "[%s] Creating builtin.filecontent condition: pattern='%s', filePattern='%s'",
                ruleId, pattern, filePattern));

        return new FileContentCondition(pattern, filePattern);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return Map.of();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}

package org.jboss.windup.rules.loader;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleCondition;
import org.jboss.windup.rules.condition.FileContentCondition;
import org.jboss.windup.rules.condition.JavaClassCondition;
import org.jboss.windup.rules.condition.XmlXPathCondition;

import java.util.logging.Logger;

/**
 * Converts YAML condition definitions into {@link RuleCondition} instances.
 * <p>
 * Currently produces stub conditions that log what they would match but return no results.
 * The actual scanning logic (AST walking, XPath evaluation, regex searching) will be
 * implemented in later phases.
 */
@ApplicationScoped
public class YamlRuleConditionFactory {

    private static final Logger LOG = Logger.getLogger(YamlRuleConditionFactory.class.getName());

    /**
     * Creates a {@link RuleCondition} from the given YAML condition definition.
     *
     * @param ruleId    the owning rule's id, used for logging
     * @param condition the parsed YAML condition block
     * @return a RuleCondition that can be evaluated during an analysis run
     * @throws IllegalArgumentException if the condition has no recognised type
     */
    public RuleCondition createCondition(String ruleId, YamlCondition condition) {
        if (condition == null) {
            return run -> ConditionResult.noMatch();
        }

        if (condition.getJavaClass() != null) {
            return createJavaClassCondition(ruleId, condition.getJavaClass());
        }
        if (condition.getXmlMatches() != null) {
            return createXmlMatchesCondition(ruleId, condition.getXmlMatches());
        }
        if (condition.getFileContent() != null) {
            return createFileContentCondition(ruleId, condition.getFileContent());
        }

        throw new IllegalArgumentException(
                "Rule '" + ruleId + "' has a 'when' block with no recognised condition type. " +
                "Supported types: java-class, xml-matches, file-content");
    }

    /**
     * Creates a condition that searches for Java class references matching the given pattern.
     * Scans {@link org.jboss.windup.java.model.JavaClassReference} models from the analysis context.
     */
    private RuleCondition createJavaClassCondition(String ruleId,
                                                    YamlCondition.JavaClassCondition javaClass) {
        String references = javaClass.getReferences();
        String location = javaClass.getLocation();

        LOG.fine(() -> String.format(
                "[%s] Creating java-class condition: references='%s', location='%s'",
                ruleId, references, location));

        return new JavaClassCondition(references, location);
    }

    /**
     * Creates a condition that searches for XML content matching an XPath expression.
     * Currently uses simplified element-name matching; full XPath evaluation will
     * be added in a later phase.
     */
    private RuleCondition createXmlMatchesCondition(String ruleId,
                                                     YamlCondition.XmlMatchesCondition xmlMatches) {
        String xpath = xmlMatches.getXpath();

        LOG.fine(() -> String.format(
                "[%s] Creating xml-matches condition: xpath='%s'",
                ruleId, xpath));

        return new XmlXPathCondition(xpath, xmlMatches.getNamespaces());
    }

    /**
     * Creates a condition that searches file content using a regular expression.
     * Scans file contents in the analysis context with the regex pattern.
     */
    private RuleCondition createFileContentCondition(String ruleId,
                                                      YamlCondition.FileContentCondition fileContent) {
        String pattern = fileContent.getPattern();
        String filename = fileContent.getFilename();

        LOG.fine(() -> String.format(
                "[%s] Creating file-content condition: pattern='%s', filename='%s'",
                ruleId, pattern, filename));

        return new FileContentCondition(pattern, filename);
    }
}

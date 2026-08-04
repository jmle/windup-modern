package org.jboss.windup.rules.loader;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.rules.action.HintAction;

import java.util.List;
import java.util.logging.Logger;

/**
 * Converts Konveyor-format flat rule fields into {@link RuleAction} instances.
 *
 * <p>In the Konveyor format, action properties ({@code description}, {@code message},
 * {@code effort}, {@code category}, {@code links}) live directly on the rule — there
 * is no nested {@code perform} block.</p>
 */
@ApplicationScoped
public class YamlRuleActionFactory {

    private static final Logger LOG = Logger.getLogger(YamlRuleActionFactory.class.getName());

    public RuleAction createAction(String ruleId, YamlRule rule) {
        if (rule == null) {
            return (run, matched) -> { };
        }

        String title = rule.getDescription();
        String message = rule.getMessage();

        if (title == null && message == null) {
            return (run, matched) -> { };
        }

        List<LinkModel> links = convertLinks(rule.getLinks());

        return new HintAction(
                ruleId,
                title,
                message,
                rule.getEffort(),
                rule.getCategory(),
                links);
    }

    private static List<LinkModel> convertLinks(List<YamlRule.Link> yamlLinks) {
        if (yamlLinks == null || yamlLinks.isEmpty()) {
            return List.of();
        }
        return yamlLinks.stream()
                .map(link -> new LinkModel(link.getTitle(), link.getUrl()))
                .toList();
    }
}

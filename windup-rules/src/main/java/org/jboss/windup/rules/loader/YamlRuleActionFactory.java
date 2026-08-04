package org.jboss.windup.rules.loader;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.rules.action.ClassificationAction;
import org.jboss.windup.rules.action.HintAction;
import org.jboss.windup.rules.action.TechnologyTagAction;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Converts YAML action definitions into {@link RuleAction} instances.
 * <p>
 * Produces real action implementations that create reporting model instances
 * ({@link org.jboss.windup.reporting.model.InlineHintModel},
 * {@link org.jboss.windup.reporting.model.ClassificationModel},
 * {@link org.jboss.windup.reporting.model.TechnologyTagModel})
 * and register them in the analysis context.
 */
@ApplicationScoped
public class YamlRuleActionFactory {

    private static final Logger LOG = Logger.getLogger(YamlRuleActionFactory.class.getName());

    /**
     * Creates a {@link RuleAction} from the given YAML action definition.
     *
     * @param ruleId the owning rule's id, used for logging and attaching to created models
     * @param action the parsed YAML action block
     * @return a RuleAction that can be performed during an analysis run
     * @throws IllegalArgumentException if the action has no recognised type
     */
    public RuleAction createAction(String ruleId, YamlAction action) {
        if (action == null) {
            return (run, matched) -> { };
        }

        if (action.getHint() != null) {
            return createHintAction(ruleId, action.getHint());
        }
        if (action.getClassification() != null) {
            return createClassificationAction(ruleId, action.getClassification());
        }
        if (action.getTechnologyTag() != null) {
            return createTechnologyTagAction(ruleId, action.getTechnologyTag());
        }

        throw new IllegalArgumentException(
                "Rule '" + ruleId + "' has a 'perform' block with no recognised action type. " +
                "Supported types: hint, classification, technology-tag");
    }

    /**
     * Creates a {@link HintAction} that produces {@link org.jboss.windup.reporting.model.InlineHintModel}
     * instances for each matched item.
     */
    private RuleAction createHintAction(String ruleId, YamlAction.HintAction hint) {
        List<LinkModel> links = collectLinks(hint.getLink(), hint.getLinks());
        return new HintAction(
                ruleId,
                hint.getTitle(),
                hint.getMessage(),
                hint.getEffort(),
                hint.getCategory(),
                links);
    }

    /**
     * Creates a {@link ClassificationAction} that produces
     * {@link org.jboss.windup.reporting.model.ClassificationModel} instances for each matched file.
     */
    private RuleAction createClassificationAction(String ruleId,
                                                   YamlAction.ClassificationAction classification) {
        List<LinkModel> links = convertLinks(classification.getLinks());
        return new ClassificationAction(
                ruleId,
                classification.getTitle(),
                classification.getDescription(),
                classification.getEffort(),
                classification.getCategory(),
                links);
    }

    /**
     * Creates a {@link TechnologyTagAction} that produces
     * {@link org.jboss.windup.reporting.model.TechnologyTagModel} instances.
     */
    private RuleAction createTechnologyTagAction(String ruleId,
                                                  YamlAction.TechnologyTagAction technologyTag) {
        return new TechnologyTagAction(
                ruleId,
                technologyTag.getTag(),
                technologyTag.getLevel());
    }

    /**
     * Collects links from both the single-link shorthand and the links list,
     * converting each to a {@link LinkModel}.
     */
    private static List<LinkModel> collectLinks(YamlAction.YamlLink singleLink,
                                                 List<YamlAction.YamlLink> linkList) {
        List<LinkModel> result = new ArrayList<>();
        if (singleLink != null) {
            result.add(new LinkModel(singleLink.getTitle(), singleLink.getUrl()));
        }
        if (linkList != null) {
            for (YamlAction.YamlLink link : linkList) {
                result.add(new LinkModel(link.getTitle(), link.getUrl()));
            }
        }
        return result;
    }

    /**
     * Converts a list of YAML links to {@link LinkModel} instances.
     */
    private static List<LinkModel> convertLinks(List<YamlAction.YamlLink> yamlLinks) {
        if (yamlLinks == null || yamlLinks.isEmpty()) {
            return List.of();
        }
        List<LinkModel> result = new ArrayList<>(yamlLinks.size());
        for (YamlAction.YamlLink link : yamlLinks) {
            result.add(new LinkModel(link.getTitle(), link.getUrl()));
        }
        return result;
    }
}

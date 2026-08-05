package org.jboss.windup.rules.action;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.reporting.model.Severity;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A {@link RuleAction} that creates {@link InlineHintModel} instances for each
 * matched item in a {@link ConditionResult}.
 * <p>
 * If the matched item is a {@link JavaClassReference}, the hint is attached at
 * the reference's line and column. If it is a {@link FileModel}, the hint is
 * attached at line 0. Other item types are silently skipped.
 */
public class HintAction implements RuleAction {

    private static final Logger LOG = Logger.getLogger(HintAction.class.getName());

    private final String ruleId;
    private final String title;
    private final String message;
    private final int effortPoints;
    private final String category;
    private final List<LinkModel> links;

    /**
     * @param ruleId       the owning rule's id
     * @param title        hint title
     * @param message      hint body text
     * @param effortPoints effort as raw story points (mapped to {@link EffortLevel})
     * @param category     category/severity string (e.g. "mandatory", "optional")
     * @param links        external documentation links (may be empty, must not be null)
     */
    public HintAction(String ruleId, String title, String message,
                      int effortPoints, String category, List<LinkModel> links) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.title = title != null ? title : "";
        this.message = message != null ? message : "";
        this.effortPoints = effortPoints;
        this.category = category;
        this.links = links != null ? List.copyOf(links) : List.of();
    }

    @Override
    public void perform(AnalysisRun run, ConditionResult matched) {
        if (matched.items() == null || matched.items().isEmpty()) {
            return;
        }

        ModelRegistry<InlineHintModel> registry =
                run.getContext().getOrCreateRegistry(InlineHintModel.class);
        EffortLevel effort = EffortLevel.fromStoryPoints(effortPoints);
        Severity severity = parseSeverity(category);

        for (Object item : matched.items()) {
            InlineHintModel hint = createHint(item);
            if (hint == null) {
                continue;
            }

            hint.setHint(message);
            hint.setEffort(effort);
            hint.setEffortPoints(effortPoints);
            hint.setSeverity(severity);
            hint.setCategory(category);
            hint.setRuleId(ruleId);

            for (LinkModel link : links) {
                hint.addLink(link);
            }

            registry.register(hint);

            LOG.fine(() -> String.format(
                    "[%s] created hint: title='%s', file='%s', line=%d",
                    ruleId, title,
                    hint.getSourceFile() != null ? hint.getSourceFile().getFileName() : "?",
                    hint.getLineNumber()));
        }
    }

    private InlineHintModel createHint(Object item) {
        if (item instanceof JavaClassReference ref) {
            FileModel sourceFile = ref.getSourceFile();
            if (sourceFile == null) {
                return null;
            }
            InlineHintModel hint = new InlineHintModel(title, sourceFile, ref.getLineNumber());
            hint.setColumnNumber(ref.getColumnNumber());
            return hint;
        } else if (item instanceof FileModel file) {
            return new InlineHintModel(title, file, 0);
        }
        return null;
    }

    /**
     * Maps a category string to a {@link Severity} value.
     * Tries exact enum match first, then falls back to common YAML rule categories.
     */
    static Severity parseSeverity(String category) {
        if (category == null || category.isBlank()) {
            return Severity.INFORMATION;
        }
        try {
            return Severity.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            return switch (category.toLowerCase()) {
                case "mandatory" -> Severity.COMPLEX;
                case "potential" -> Severity.TRIVIAL;
                default -> Severity.INFORMATION;
            };
        }
    }

    // -- accessors for testing --

    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public int getEffortPoints() { return effortPoints; }
    public String getCategory() { return category; }
    public List<LinkModel> getLinks() { return links; }
}

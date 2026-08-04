package org.jboss.windup.rules.action;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.LinkModel;
import org.jboss.windup.reporting.model.Severity;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A {@link RuleAction} that creates {@link ClassificationModel} instances for
 * each unique source file associated with matched items in a {@link ConditionResult}.
 * <p>
 * Deduplicates by file path: only one classification is created per file per
 * rule execution, even if multiple matched items refer to the same file.
 */
public class ClassificationAction implements RuleAction {

    private static final Logger LOG = Logger.getLogger(ClassificationAction.class.getName());

    private final String ruleId;
    private final String title;
    private final String description;
    private final int effortPoints;
    private final String category;
    private final List<LinkModel> links;

    /**
     * @param ruleId       the owning rule's id
     * @param title        classification title
     * @param description  classification body text
     * @param effortPoints effort as raw story points (mapped to {@link EffortLevel})
     * @param category     category/severity string
     * @param links        external documentation links (may be empty, must not be null)
     */
    public ClassificationAction(String ruleId, String title, String description,
                                int effortPoints, String category, List<LinkModel> links) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.title = title != null ? title : "";
        this.description = description != null ? description : "";
        this.effortPoints = effortPoints;
        this.category = category;
        this.links = links != null ? List.copyOf(links) : List.of();
    }

    @Override
    public void perform(AnalysisRun run, ConditionResult matched) {
        if (matched.items() == null || matched.items().isEmpty()) {
            return;
        }

        ModelRegistry<ClassificationModel> registry =
                run.getContext().getOrCreateRegistry(ClassificationModel.class);
        EffortLevel effort = EffortLevel.fromStoryPoints(effortPoints);
        Severity severity = HintAction.parseSeverity(category);

        Set<Path> classifiedFiles = new HashSet<>();

        for (Object item : matched.items()) {
            FileModel sourceFile = extractFile(item);
            if (sourceFile == null) {
                continue;
            }

            // Deduplicate: one classification per file per action execution
            if (!classifiedFiles.add(sourceFile.getFilePath())) {
                continue;
            }

            ClassificationModel model = new ClassificationModel(title, sourceFile);
            model.setDescription(description);
            model.setEffort(effort);
            model.setSeverity(severity);
            model.setRuleId(ruleId);

            for (LinkModel link : links) {
                model.addLink(link);
            }

            registry.register(model);

            LOG.fine(() -> String.format(
                    "[%s] created classification: title='%s', file='%s'",
                    ruleId, title, sourceFile.getFileName()));
        }
    }

    /**
     * Extracts the source {@link FileModel} from a matched item.
     */
    private static FileModel extractFile(Object item) {
        if (item instanceof JavaClassReference ref) {
            return ref.getSourceFile();
        } else if (item instanceof FileModel file) {
            return file;
        }
        return null;
    }

    // -- accessors for testing --

    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getEffortPoints() { return effortPoints; }
    public String getCategory() { return category; }
    public List<LinkModel> getLinks() { return links; }
}

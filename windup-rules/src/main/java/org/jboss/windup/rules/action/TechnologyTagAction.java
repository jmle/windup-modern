package org.jboss.windup.rules.action;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleAction;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.TechnologyTagLevel;
import org.jboss.windup.reporting.model.TechnologyTagModel;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A {@link RuleAction} that creates {@link TechnologyTagModel} instances and
 * registers them in the analysis context.
 * <p>
 * One tag is registered for each unique source file among the matched items.
 */
public class TechnologyTagAction implements RuleAction {

    private static final Logger LOG = Logger.getLogger(TechnologyTagAction.class.getName());

    private final String ruleId;
    private final String tagName;
    private final String level;

    /**
     * @param ruleId  the owning rule's id
     * @param tagName short technology identifier (e.g. "EJB")
     * @param level   importance level string (e.g. "INFORMATIONAL", "IMPORTANT")
     */
    public TechnologyTagAction(String ruleId, String tagName, String level) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.tagName = tagName != null ? tagName : "";
        this.level = level;
    }

    @Override
    public void perform(AnalysisRun run, ConditionResult matched) {
        if (matched.items() == null || matched.items().isEmpty()) {
            return;
        }

        ModelRegistry<TechnologyTagModel> registry =
                run.getContext().getOrCreateRegistry(TechnologyTagModel.class);
        TechnologyTagLevel tagLevel = parseLevel(level);

        Set<Path> taggedFiles = new HashSet<>();

        for (Object item : matched.items()) {
            FileModel sourceFile = extractFile(item);
            if (sourceFile == null) {
                continue;
            }

            // One tag per file
            if (!taggedFiles.add(sourceFile.getFilePath())) {
                continue;
            }

            TechnologyTagModel tag = new TechnologyTagModel(tagName, tagLevel);
            registry.register(tag);

            LOG.fine(() -> String.format(
                    "[%s] tagged file '%s' with technology '%s' (level=%s)",
                    ruleId, sourceFile.getFileName(), tagName, tagLevel));
        }
    }

    /**
     * Parses a level string to a {@link TechnologyTagLevel}, defaulting to
     * {@link TechnologyTagLevel#INFORMATIONAL} if the string is null or unrecognised.
     */
    static TechnologyTagLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return TechnologyTagLevel.INFORMATIONAL;
        }
        try {
            return TechnologyTagLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TechnologyTagLevel.INFORMATIONAL;
        }
    }

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
    public String getTagName() { return tagName; }
    public String getLevel() { return level; }
}

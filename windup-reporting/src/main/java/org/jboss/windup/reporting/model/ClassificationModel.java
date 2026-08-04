package org.jboss.windup.reporting.model;

import org.jboss.windup.model.FileModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A classification applied to a source file, providing general background information
 * about its purpose or migration impact. For instance, an XML file may be classified
 * as a "Spring Configuration File".
 * <p>
 * A classification may also contain links to additional documentation or resources.
 */
public class ClassificationModel {

    private String title;
    private String description;
    private EffortLevel effort;
    private Severity severity;
    private final List<LinkModel> links = new ArrayList<>();
    private FileModel sourceFile;
    private String ruleId;

    public ClassificationModel() {
    }

    public ClassificationModel(String title, FileModel sourceFile) {
        this.title = Objects.requireNonNull(title, "title");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public EffortLevel getEffort() {
        return effort;
    }

    public void setEffort(EffortLevel effort) {
        this.effort = effort;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    /**
     * Returns an unmodifiable view of the links associated with this classification.
     */
    public List<LinkModel> getLinks() {
        return Collections.unmodifiableList(links);
    }

    public void addLink(LinkModel link) {
        links.add(Objects.requireNonNull(link, "link"));
    }

    public FileModel getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(FileModel sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * The ID of the rule that produced this classification.
     */
    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    @Override
    public String toString() {
        return "ClassificationModel{" +
                "title='" + title + '\'' +
                ", ruleId='" + ruleId + '\'' +
                ", effort=" + effort +
                ", severity=" + severity +
                '}';
    }
}

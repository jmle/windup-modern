package org.jboss.windup.reporting.model;

import org.jboss.windup.model.FileModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A hint pointing to a specific location within a source file, providing
 * guidance on a migration issue found at that position.
 */
public class InlineHintModel {

    private String title;
    private String hint;
    private EffortLevel effort;
    private int effortPoints;
    private Severity severity;
    private String category;
    private int lineNumber;
    private int columnNumber;
    private final List<LinkModel> links = new ArrayList<>();
    private FileModel sourceFile;
    private String ruleId;

    public InlineHintModel() {
    }

    public InlineHintModel(String title, FileModel sourceFile, int lineNumber) {
        this.title = Objects.requireNonNull(title, "title");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
        this.lineNumber = lineNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * The hint text describing the migration issue and recommended action.
     */
    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public EffortLevel getEffort() {
        return effort;
    }

    public void setEffort(EffortLevel effort) {
        this.effort = effort;
    }

    public int getEffortPoints() {
        return effortPoints;
    }

    public void setEffortPoints(int effortPoints) {
        this.effortPoints = effortPoints;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public void setColumnNumber(int columnNumber) {
        this.columnNumber = columnNumber;
    }

    /**
     * Returns an unmodifiable view of the links associated with this hint.
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
     * The ID of the rule that produced this hint.
     */
    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    @Override
    public String toString() {
        return "InlineHintModel{" +
                "title='" + title + '\'' +
                ", ruleId='" + ruleId + '\'' +
                ", lineNumber=" + lineNumber +
                ", effort=" + effort +
                ", severity=" + severity +
                '}';
    }
}

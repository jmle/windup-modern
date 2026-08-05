package org.jboss.windup.reporting.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"name", "description", "tags", "violations", "unmatched", "skipped"})
public class RuleSetViolation {

    private String name;
    private String description;
    private List<String> tags;
    private Map<String, Violation> violations;
    private List<String> unmatched;
    private List<String> skipped;

    public RuleSetViolation() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Map<String, Violation> getViolations() {
        return violations;
    }

    public void setViolations(Map<String, Violation> violations) {
        this.violations = violations;
    }

    public List<String> getUnmatched() {
        return unmatched;
    }

    public void setUnmatched(List<String> unmatched) {
        this.unmatched = unmatched;
    }

    public List<String> getSkipped() {
        return skipped;
    }

    public void setSkipped(List<String> skipped) {
        this.skipped = skipped;
    }
}

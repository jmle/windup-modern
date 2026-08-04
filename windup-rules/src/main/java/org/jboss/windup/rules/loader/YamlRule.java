package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Represents a single rule in the Konveyor YAML format.
 * Rules are top-level array elements — no wrapper object.
 *
 * <pre>
 * - ruleID: my-rule-001
 *   description: "Title shown in reports"
 *   message: "Detailed migration guidance"
 *   effort: 1
 *   category: mandatory
 *   when:
 *     java.referenced:
 *       pattern: "javax.ejb.{*}"
 *       location: ANNOTATION
 *   links:
 *     - title: "Migration Guide"
 *       url: "https://example.com"
 *   tag:
 *     - "EJB"
 *   labels:
 *     - "konveyor.io/source=eap6"
 *     - "konveyor.io/target=eap7"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRule {

    @JsonProperty("ruleID")
    private String ruleID;

    private String description;
    private String message;
    private int effort;
    private String category;
    private List<String> labels;
    private List<Link> links;
    private List<String> tag;
    private Map<String, Object> when;
    private List<CustomVariable> customVariables;

    public YamlRule() {
    }

    public String getRuleID() {
        return ruleID;
    }

    public void setRuleID(String ruleID) {
        this.ruleID = ruleID;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getEffort() {
        return effort;
    }

    public void setEffort(int effort) {
        this.effort = effort;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public List<Link> getLinks() {
        return links;
    }

    public void setLinks(List<Link> links) {
        this.links = links;
    }

    public List<String> getTag() {
        return tag;
    }

    public void setTag(List<String> tag) {
        this.tag = tag;
    }

    public Map<String, Object> getWhen() {
        return when;
    }

    public void setWhen(Map<String, Object> when) {
        this.when = when;
    }

    public List<CustomVariable> getCustomVariables() {
        return customVariables;
    }

    public void setCustomVariables(List<CustomVariable> customVariables) {
        this.customVariables = customVariables;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {

        private String title;
        private String url;

        public Link() {
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomVariable {

        private String name;
        private String pattern;
        private String defaultValue;
        private String nameOfCaptureGroup;

        public CustomVariable() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String getNameOfCaptureGroup() {
            return nameOfCaptureGroup;
        }

        public void setNameOfCaptureGroup(String nameOfCaptureGroup) {
            this.nameOfCaptureGroup = nameOfCaptureGroup;
        }
    }
}

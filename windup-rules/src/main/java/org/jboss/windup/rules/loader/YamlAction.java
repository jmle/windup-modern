package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents the {@code perform} block of a YAML rule.
 * <p>
 * Exactly one of the action subtypes should be populated:
 * <ul>
 *     <li>{@code hint} - creates an inline hint on a specific code location</li>
 *     <li>{@code classification} - classifies a file with a description</li>
 *     <li>{@code technology-tag} - tags a file with a detected technology</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlAction {

    private HintAction hint;
    private ClassificationAction classification;

    @JsonProperty("technology-tag")
    private TechnologyTagAction technologyTag;

    public YamlAction() {
    }

    public HintAction getHint() {
        return hint;
    }

    public void setHint(HintAction hint) {
        this.hint = hint;
    }

    public ClassificationAction getClassification() {
        return classification;
    }

    public void setClassification(ClassificationAction classification) {
        this.classification = classification;
    }

    public TechnologyTagAction getTechnologyTag() {
        return technologyTag;
    }

    public void setTechnologyTag(TechnologyTagAction technologyTag) {
        this.technologyTag = technologyTag;
    }

    /**
     * Returns the type name of the action that is populated, or {@code "unknown"}.
     */
    public String getActionType() {
        if (hint != null) return "hint";
        if (classification != null) return "classification";
        if (technologyTag != null) return "technology-tag";
        return "unknown";
    }

    // ---- Action subtypes ----

    /**
     * An inline hint action providing migration guidance at a specific code location.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HintAction {

        private String title;
        private String message;
        private int effort;
        private String category;
        private YamlLink link;
        private List<YamlLink> links;

        public HintAction() {
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
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

        /**
         * A single link (convenience shorthand for rules with one link).
         */
        public YamlLink getLink() {
            return link;
        }

        public void setLink(YamlLink link) {
            this.link = link;
        }

        /**
         * Multiple links associated with this hint.
         */
        public List<YamlLink> getLinks() {
            return links;
        }

        public void setLinks(List<YamlLink> links) {
            this.links = links;
        }
    }

    /**
     * A classification action that labels a file with a description of its purpose
     * or migration impact.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClassificationAction {

        private String title;
        private String description;
        private int effort;
        private String category;
        private List<YamlLink> links;

        public ClassificationAction() {
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

        public List<YamlLink> getLinks() {
            return links;
        }

        public void setLinks(List<YamlLink> links) {
            this.links = links;
        }
    }

    /**
     * A technology tag action that marks a file with a detected technology.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TechnologyTagAction {

        private String tag;
        private String level;

        public TechnologyTagAction() {
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        /**
         * The importance level (e.g., INFORMATIONAL, IMPORTANT).
         */
        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }

    /**
     * A link to external documentation or resources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YamlLink {

        private String title;
        private String url;

        public YamlLink() {
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
}

package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Represents the {@code when} block of a YAML rule.
 * <p>
 * Exactly one of the condition subtypes should be populated:
 * <ul>
 *     <li>{@code java-class} - matches Java class references</li>
 *     <li>{@code xml-matches} - matches XML content via XPath</li>
 *     <li>{@code file-content} - matches file content via regex</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlCondition {

    @JsonProperty("java-class")
    private JavaClassCondition javaClass;

    @JsonProperty("xml-matches")
    private XmlMatchesCondition xmlMatches;

    @JsonProperty("file-content")
    private FileContentCondition fileContent;

    public YamlCondition() {
    }

    public JavaClassCondition getJavaClass() {
        return javaClass;
    }

    public void setJavaClass(JavaClassCondition javaClass) {
        this.javaClass = javaClass;
    }

    public XmlMatchesCondition getXmlMatches() {
        return xmlMatches;
    }

    public void setXmlMatches(XmlMatchesCondition xmlMatches) {
        this.xmlMatches = xmlMatches;
    }

    public FileContentCondition getFileContent() {
        return fileContent;
    }

    public void setFileContent(FileContentCondition fileContent) {
        this.fileContent = fileContent;
    }

    /**
     * Returns the type name of the condition that is populated, or {@code "unknown"}.
     */
    public String getConditionType() {
        if (javaClass != null) return "java-class";
        if (xmlMatches != null) return "xml-matches";
        if (fileContent != null) return "file-content";
        return "unknown";
    }

    // ---- Condition subtypes ----

    /**
     * Condition that searches for Java class references matching a pattern.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JavaClassCondition {

        private String references;
        private String location;

        public JavaClassCondition() {
        }

        public String getReferences() {
            return references;
        }

        public void setReferences(String references) {
            this.references = references;
        }

        /**
         * The reference location filter (e.g., ANNOTATION, IMPORT, METHOD_CALL).
         * Maps to {@link org.jboss.windup.java.model.JavaClassReference.ReferenceType}.
         */
        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }

    /**
     * Condition that searches for XML content matching an XPath expression.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XmlMatchesCondition {

        private String xpath;
        private Map<String, String> namespaces;

        public XmlMatchesCondition() {
        }

        public String getXpath() {
            return xpath;
        }

        public void setXpath(String xpath) {
            this.xpath = xpath;
        }

        public Map<String, String> getNamespaces() {
            return namespaces;
        }

        public void setNamespaces(Map<String, String> namespaces) {
            this.namespaces = namespaces;
        }
    }

    /**
     * Condition that searches file content using a regular expression.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileContentCondition {

        private String pattern;
        private String filename;

        public FileContentCondition() {
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        /**
         * Optional filename filter (glob pattern) to restrict which files are searched.
         */
        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }
    }
}

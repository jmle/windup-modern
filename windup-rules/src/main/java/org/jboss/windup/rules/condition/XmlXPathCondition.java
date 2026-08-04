package org.jboss.windup.rules.condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.RuleCondition;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.rules.model.XmlFileModel;
import org.jboss.windup.rules.model.XmlTypeReferenceModel;

/**
 * Matches XML files in the analysis context using an XPath expression.
 *
 * <p>Currently performs a simplified match: scans all {@link XmlFileModel}
 * instances and checks whether the XPath expression string contains the
 * root element name. Full XPath evaluation (using Saxon or javax.xml.xpath)
 * will be implemented in a later phase.</p>
 *
 * <p>Matched files are returned as {@link XmlTypeReferenceModel} items in the
 * condition result.</p>
 */
public class XmlXPathCondition implements RuleCondition {

    private static final Logger LOG = Logger.getLogger(XmlXPathCondition.class.getName());

    private final String xpath;
    private final Map<String, String> namespaces;

    /**
     * Creates a new XML XPath condition.
     *
     * @param xpath      the XPath expression to match (must not be null)
     * @param namespaces optional namespace prefix-to-URI mappings; may be null
     */
    public XmlXPathCondition(String xpath, Map<String, String> namespaces) {
        if (xpath == null || xpath.isBlank()) {
            throw new IllegalArgumentException("xpath must not be null or blank");
        }
        this.xpath = xpath;
        this.namespaces = namespaces != null ? Map.copyOf(namespaces) : Map.of();
    }

    @Override
    public ConditionResult evaluate(AnalysisRun run) {
        if (run == null || run.getContext() == null) {
            return ConditionResult.noMatch();
        }

        ModelRegistry<XmlFileModel> xmlRegistry =
                run.getContext().getOrCreateRegistry(XmlFileModel.class);
        List<XmlFileModel> xmlFiles = xmlRegistry.findAll();

        if (xmlFiles.isEmpty()) {
            LOG.fine(() -> String.format("xml-matches condition xpath='%s': no XML files in context", xpath));
            return ConditionResult.noMatch();
        }

        // Simplified matching: extract the local element name from the XPath
        // and match it against each XML file's root element name.
        String targetElement = extractElementName(xpath);

        List<XmlTypeReferenceModel> matched = new ArrayList<>();
        for (XmlFileModel xmlFile : xmlFiles) {
            if (xmlFile.getRootElementName() == null) {
                continue;
            }
            if (targetElement != null && xmlFile.getRootElementName().equals(targetElement)) {
                XmlTypeReferenceModel ref = new XmlTypeReferenceModel();
                ref.setXpath(xpath);
                ref.setMatchedContent(xmlFile.getRootElementName());
                ref.setLineNumber(1);
                ref.setColumnNumber(1);
                ref.setSourceFile(xmlFile);
                matched.add(ref);
            }
        }

        if (matched.isEmpty()) {
            LOG.fine(() -> String.format(
                    "xml-matches condition xpath='%s': no matches among %d XML files",
                    xpath, xmlFiles.size()));
            return ConditionResult.noMatch();
        }

        LOG.fine(() -> String.format(
                "xml-matches condition xpath='%s': matched %d XML files",
                xpath, matched.size()));
        return ConditionResult.match(matched);
    }

    /**
     * Extracts a simple element name from an XPath expression.
     * <p>
     * This is a placeholder heuristic that handles common patterns like:
     * <ul>
     *     <li>{@code //ejb-jar} -> {@code ejb-jar}</li>
     *     <li>{@code //jee:ejb-jar} -> {@code ejb-jar}</li>
     *     <li>{@code /web-app/servlet} -> {@code servlet}</li>
     * </ul>
     * Full XPath parsing will replace this in a later phase.
     */
    static String extractElementName(String xpath) {
        if (xpath == null || xpath.isBlank()) {
            return null;
        }
        // Take the last path segment
        String segment = xpath;
        int lastSlash = segment.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < segment.length() - 1) {
            segment = segment.substring(lastSlash + 1);
        }
        // Strip any predicate like [...]
        int bracket = segment.indexOf('[');
        if (bracket > 0) {
            segment = segment.substring(0, bracket);
        }
        // Strip namespace prefix
        int colon = segment.indexOf(':');
        if (colon >= 0 && colon < segment.length() - 1) {
            segment = segment.substring(colon + 1);
        }
        // Strip any remaining whitespace
        segment = segment.trim();
        return segment.isEmpty() ? null : segment;
    }

    /** Returns the XPath expression. */
    public String getXpath() {
        return xpath;
    }

    /** Returns the namespace mappings (unmodifiable). */
    public Map<String, String> getNamespaces() {
        return namespaces;
    }
}

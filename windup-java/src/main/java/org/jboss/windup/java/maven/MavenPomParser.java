package org.jboss.windup.java.maven;

import org.jboss.windup.java.model.MavenProjectModel;
import org.jboss.windup.model.DependencyModel;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses Maven POM files ({@code pom.xml}) and creates {@link MavenProjectModel}
 * instances populated with the extracted metadata.
 *
 * <p>This is a plain utility class (not CDI-managed). It uses the standard
 * {@link DocumentBuilder} API to parse the XML and extracts the following
 * information:</p>
 * <ul>
 *   <li>Project coordinates: groupId, artifactId, version, packaging</li>
 *   <li>Project descriptors: name, description</li>
 *   <li>Parent project reference (creates a linked {@link MavenProjectModel})</li>
 *   <li>Dependencies (creates {@link DependencyModel} instances)</li>
 * </ul>
 *
 * <p>Simple property interpolation is supported for
 * {@code ${project.version}}, {@code ${project.groupId}},
 * {@code ${project.artifactId}}, and user-defined properties declared in the
 * {@code <properties>} section.</p>
 */
public class MavenPomParser {

    private static final Logger LOG = Logger.getLogger(MavenPomParser.class.getName());

    private final DocumentBuilderFactory dbFactory;

    public MavenPomParser() {
        this.dbFactory = DocumentBuilderFactory.newInstance();
        // Disable external entities for security
        try {
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            LOG.log(Level.FINE, "Could not disable doctype declaration feature", e);
        }
    }

    /**
     * Parses the given {@code pom.xml} file and returns a populated
     * {@link MavenProjectModel}.
     *
     * @param pomFile path to the {@code pom.xml} file
     * @return the parsed project model, never {@code null}
     * @throws MavenPomParseException if the file cannot be parsed
     */
    public MavenProjectModel parse(Path pomFile) {
        try {
            DocumentBuilder builder = dbFactory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            return parseProjectElement(root);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new MavenPomParseException("Failed to parse POM file: " + pomFile, e);
        }
    }

    private MavenProjectModel parseProjectElement(Element root) {
        MavenProjectModel model = new MavenProjectModel();

        // Collect user-defined properties first
        Map<String, String> properties = parseProperties(root);

        // Parse parent (before GAV so we can inherit from it)
        MavenProjectModel parentModel = parseParent(root);
        if (parentModel != null) {
            model.setParentMavenProject(parentModel);
            model.setParentProject(parentModel);
        }

        // Core coordinates
        String groupId = getDirectChildText(root, "groupId");
        String artifactId = getDirectChildText(root, "artifactId");
        String version = getDirectChildText(root, "version");
        String packaging = getDirectChildText(root, "packaging");
        String name = getDirectChildText(root, "name");
        String description = getDirectChildText(root, "description");

        // Inherit from parent if not specified
        if (groupId == null && parentModel != null) {
            groupId = parentModel.getGroupId();
        }
        if (version == null && parentModel != null) {
            version = parentModel.getMavenVersion();
        }

        // Populate built-in project properties for interpolation
        if (groupId != null) {
            properties.put("project.groupId", groupId);
        }
        if (artifactId != null) {
            properties.put("project.artifactId", artifactId);
        }
        if (version != null) {
            properties.put("project.version", version);
        }

        model.setGroupId(interpolate(groupId, properties));
        model.setArtifactId(interpolate(artifactId, properties));
        model.setMavenVersion(interpolate(version, properties));
        model.setVersion(interpolate(version, properties));
        model.setPackaging(packaging != null ? interpolate(packaging, properties) : "jar");
        model.setName(interpolate(name, properties));
        model.setDescription(interpolate(description, properties));

        // Dependencies
        parseDependencies(root, properties, model);

        return model;
    }

    private MavenProjectModel parseParent(Element root) {
        Element parentElement = getDirectChildElement(root, "parent");
        if (parentElement == null) {
            return null;
        }

        MavenProjectModel parent = new MavenProjectModel();
        parent.setGroupId(getDirectChildText(parentElement, "groupId"));
        parent.setArtifactId(getDirectChildText(parentElement, "artifactId"));
        String parentVersion = getDirectChildText(parentElement, "version");
        parent.setMavenVersion(parentVersion);
        parent.setVersion(parentVersion);
        return parent;
    }

    private Map<String, String> parseProperties(Element root) {
        Map<String, String> properties = new HashMap<>();
        Element propsElement = getDirectChildElement(root, "properties");
        if (propsElement == null) {
            return properties;
        }

        NodeList children = propsElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                properties.put(child.getNodeName(), child.getTextContent().trim());
            }
        }
        return properties;
    }

    private void parseDependencies(Element root, Map<String, String> properties, MavenProjectModel model) {
        Element depsElement = getDirectChildElement(root, "dependencies");
        if (depsElement == null) {
            return;
        }

        NodeList depNodes = depsElement.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            Node node = depNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            // Only process direct children of <dependencies>, not nested ones
            // (e.g. from dependencyManagement/dependencies/dependency/exclusions)
            if (!node.getParentNode().equals(depsElement)) {
                continue;
            }

            Element depElement = (Element) node;
            String depGroupId = interpolate(getDirectChildText(depElement, "groupId"), properties);
            String depArtifactId = interpolate(getDirectChildText(depElement, "artifactId"), properties);
            String depVersion = interpolate(getDirectChildText(depElement, "version"), properties);
            String depClassifier = interpolate(getDirectChildText(depElement, "classifier"), properties);
            String depScope = interpolate(getDirectChildText(depElement, "scope"), properties);

            DependencyModel dep = new DependencyModel(depGroupId, depArtifactId, depVersion, depClassifier, depScope);
            model.getDependencies().add(dep);
        }
    }

    /**
     * Performs simple property interpolation on the given value. Replaces
     * occurrences of {@code ${property.name}} with the corresponding value
     * from the properties map. Unresolved placeholders are left unchanged.
     */
    String interpolate(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        String result = value;
        // Iterative replacement to handle nested references (up to a limit)
        for (int pass = 0; pass < 5; pass++) {
            boolean replaced = false;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                if (result.contains(placeholder)) {
                    result = result.replace(placeholder, entry.getValue());
                    replaced = true;
                }
            }
            if (!replaced || !result.contains("${")) {
                break;
            }
        }
        return result;
    }

    /**
     * Returns the text content of the first direct child element with the
     * given tag name, or {@code null} if no such element exists.
     */
    private static String getDirectChildText(Element parent, String tagName) {
        Element child = getDirectChildElement(parent, tagName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text != null ? text.trim() : null;
    }

    /**
     * Returns the first direct child element with the given tag name,
     * or {@code null} if no such element exists.
     */
    private static Element getDirectChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }
}

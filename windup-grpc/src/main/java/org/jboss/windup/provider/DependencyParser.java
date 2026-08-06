package org.jboss.windup.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Static parser for Maven {@code pom.xml} and Gradle {@code build.gradle} files. Extracts
 * declared dependencies without running external build tool commands. Used as a fallback
 * when build tool execution fails or is unavailable.
 */
public class DependencyParser {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyParser.class);

    public record ParsedDependency(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String scope,
            int lineNumber,
            String fileUri
    ) {
        public String name() {
            return groupId + "." + artifactId;
        }
    }

    public List<ParsedDependency> parseDirectory(Path root) {
        List<ParsedDependency> allDeps = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if ("pom.xml".equals(name)) {
                        allDeps.addAll(parsePom(file));
                    } else if ("build.gradle".equals(name)) {
                        allDeps.addAll(parseGradle(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Error walking directory for dependencies: {}", e.getMessage());
        }

        return allDeps;
    }

    List<ParsedDependency> parsePom(Path pomFile) {
        List<ParsedDependency> deps = new ArrayList<>();
        String fileUri = pomFile.toUri().toString();

        try {
            String content = Files.readString(pomFile);
            String[] lines = content.split("\n");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            Map<String, String> properties = parseProperties(doc.getDocumentElement());

            String projectGroupId = getDirectChildText(doc.getDocumentElement(), "groupId");
            String projectVersion = getDirectChildText(doc.getDocumentElement(), "version");
            Element parentEl = getDirectChildElement(doc.getDocumentElement(), "parent");
            if (parentEl != null) {
                if (projectGroupId == null) projectGroupId = getDirectChildText(parentEl, "groupId");
                if (projectVersion == null) projectVersion = getDirectChildText(parentEl, "version");
            }
            if (projectGroupId != null) properties.put("project.groupId", projectGroupId);
            if (projectVersion != null) properties.put("project.version", projectVersion);

            Element depsElement = getDirectChildElement(doc.getDocumentElement(), "dependencies");
            if (depsElement == null) return deps;

            NodeList depNodes = depsElement.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Node node = depNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!node.getParentNode().equals(depsElement)) continue;

                Element depEl = (Element) node;
                String groupId = interpolate(getDirectChildText(depEl, "groupId"), properties);
                String artifactId = interpolate(getDirectChildText(depEl, "artifactId"), properties);
                String version = interpolate(getDirectChildText(depEl, "version"), properties);
                String classifier = interpolate(getDirectChildText(depEl, "classifier"), properties);
                String scope = interpolate(getDirectChildText(depEl, "scope"), properties);

                int lineNum = findDependencyLine(lines, groupId, artifactId);

                deps.add(new ParsedDependency(
                        groupId != null ? groupId : "",
                        artifactId != null ? artifactId : "",
                        version != null ? version : "",
                        classifier,
                        scope,
                        lineNum,
                        fileUri
                ));
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse pom.xml {}: {}", pomFile, e.getMessage());
        }

        return deps;
    }

    List<ParsedDependency> parseGradle(Path gradleFile) {
        List<ParsedDependency> deps = new ArrayList<>();
        String fileUri = gradleFile.toUri().toString();

        try {
            List<String> lines = Files.readAllLines(gradleFile);
            boolean inDeps = false;
            int braceDepth = 0;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();

                if (line.startsWith("dependencies")) {
                    inDeps = true;
                    braceDepth = 0;
                }

                if (inDeps) {
                    for (char c : line.toCharArray()) {
                        if (c == '{') braceDepth++;
                        if (c == '}') braceDepth--;
                    }

                    // Match patterns like: implementation 'group:artifact:version'
                    // or: testImplementation "group:artifact:version"
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(?:implementation|api|compile|runtime|testImplementation|testCompile|testRuntime|classpath|compileOnly|runtimeOnly)\\s+['\"]([^'\":]+):([^'\":]+):([^'\"]+)['\"]")
                            .matcher(line);
                    if (m.find()) {
                        String groupId = m.group(1);
                        String artifactId = m.group(2);
                        String version = m.group(3);
                        deps.add(new ParsedDependency(groupId, artifactId, version, null, null, i, fileUri));
                    }

                    if (braceDepth <= 0 && inDeps && line.contains("}")) {
                        inDeps = false;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to parse build.gradle {}: {}", gradleFile, e.getMessage());
        }

        return deps;
    }

    private int findDependencyLine(String[] lines, String groupId, String artifactId) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("<groupId>") && groupId != null && lines[i].contains(groupId)) {
                return i;
            }
        }
        return 0;
    }

    private Map<String, String> parseProperties(Element root) {
        Map<String, String> properties = new HashMap<>();
        Element propsElement = getDirectChildElement(root, "properties");
        if (propsElement == null) return properties;

        NodeList children = propsElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                properties.put(child.getNodeName(), child.getTextContent().trim());
            }
        }
        return properties;
    }

    private String interpolate(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) return value;
        String result = value;
        for (int pass = 0; pass < 5; pass++) {
            boolean replaced = false;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                if (result.contains(placeholder)) {
                    result = result.replace(placeholder, entry.getValue());
                    replaced = true;
                }
            }
            if (!replaced || !result.contains("${")) break;
        }
        return result;
    }

    private static String getDirectChildText(Element parent, String tagName) {
        Element child = getDirectChildElement(parent, tagName);
        return child != null ? child.getTextContent().trim() : null;
    }

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

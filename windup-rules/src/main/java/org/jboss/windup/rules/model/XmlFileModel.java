package org.jboss.windup.rules.model;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jboss.windup.model.FileModel;

/**
 * Represents an XML file discovered during analysis.
 * Extends {@link FileModel} with XML-specific metadata such as the root element
 * name/namespace, parse status, and declared namespaces.
 */
public class XmlFileModel extends FileModel {

    private String rootElementName;
    private String rootElementNamespace;
    private boolean documentParsed;
    private String parseError;
    private Map<String, String> namespaces = new HashMap<>();

    public XmlFileModel(Path filePath) {
        super(filePath);
    }

    public String getRootElementName() {
        return rootElementName;
    }

    public void setRootElementName(String rootElementName) {
        this.rootElementName = rootElementName;
    }

    public String getRootElementNamespace() {
        return rootElementNamespace;
    }

    public void setRootElementNamespace(String rootElementNamespace) {
        this.rootElementNamespace = rootElementNamespace;
    }

    public boolean isDocumentParsed() {
        return documentParsed;
    }

    public void setDocumentParsed(boolean documentParsed) {
        this.documentParsed = documentParsed;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    public Map<String, String> getNamespaces() {
        return namespaces;
    }

    public void setNamespaces(Map<String, String> namespaces) {
        this.namespaces = namespaces;
    }
}

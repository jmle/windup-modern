package org.jboss.windup.rules.model;

import org.jboss.windup.model.FileModel;

/**
 * Represents an XPath match found within an XML file during analysis.
 * Captures the matched XPath expression, the content that was matched,
 * and the location within the source file.
 */
public class XmlTypeReferenceModel {

    private String xpath;
    private String matchedContent;
    private int lineNumber;
    private int columnNumber;
    private FileModel sourceFile;

    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }

    public String getMatchedContent() {
        return matchedContent;
    }

    public void setMatchedContent(String matchedContent) {
        this.matchedContent = matchedContent;
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

    public FileModel getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(FileModel sourceFile) {
        this.sourceFile = sourceFile;
    }
}

package org.jboss.windup.reporting.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a generated report, binding a template to its output location
 * and any related resources used during report generation.
 */
public class ReportModel {

    private String templatePath;
    private String reportDirectory;
    private String reportFilename;
    private String title;
    private String description;
    private final Map<String, Object> relatedResources = new LinkedHashMap<>();

    public ReportModel() {
    }

    public ReportModel(String title, String templatePath) {
        this.title = title;
        this.templatePath = templatePath;
    }

    /**
     * The path to the template used to produce this report (e.g. "/reports/migration-issues.ftl").
     */
    public String getTemplatePath() {
        return templatePath;
    }

    public void setTemplatePath(String templatePath) {
        this.templatePath = templatePath;
    }

    /**
     * The directory where this report is written.
     */
    public String getReportDirectory() {
        return reportDirectory;
    }

    public void setReportDirectory(String reportDirectory) {
        this.reportDirectory = reportDirectory;
    }

    /**
     * The filename of the report on disk.
     */
    public String getReportFilename() {
        return reportFilename;
    }

    public void setReportFilename(String reportFilename) {
        this.reportFilename = reportFilename;
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

    /**
     * Returns an unmodifiable view of the related resources map.
     * Keys are resource names; values are the associated objects.
     */
    public Map<String, Object> getRelatedResources() {
        return Collections.unmodifiableMap(relatedResources);
    }

    public void putRelatedResource(String key, Object value) {
        relatedResources.put(key, value);
    }

    @Override
    public String toString() {
        return "ReportModel{" +
                "title='" + title + '\'' +
                ", reportFilename='" + reportFilename + '\'' +
                ", templatePath='" + templatePath + '\'' +
                '}';
    }
}

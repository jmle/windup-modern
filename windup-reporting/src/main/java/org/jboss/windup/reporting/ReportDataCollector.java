package org.jboss.windup.reporting;

import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.reporting.model.ClassificationModel;
import org.jboss.windup.reporting.model.EffortLevel;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.Severity;
import org.jboss.windup.reporting.model.TechnologyTagModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collects and aggregates report data from an {@link AnalysisContext},
 * producing a {@link ReportSummary} with counts by severity, total effort,
 * technology tags, and more.
 */
public final class ReportDataCollector {

    private ReportDataCollector() {
        // utility class
    }

    /**
     * Aggregates hints and classifications from the context into a summary.
     */
    public static ReportSummary collectSummary(AnalysisContext context) {
        List<InlineHintModel> hints = context.getOrCreateRegistry(InlineHintModel.class).findAll();
        List<ClassificationModel> classifications = context.getOrCreateRegistry(ClassificationModel.class).findAll();
        List<TechnologyTagModel> tags = context.getOrCreateRegistry(TechnologyTagModel.class).findAll();

        int totalFiles = context.files().size();
        int totalIncidents = hints.size() + classifications.size();

        int totalStoryPoints = 0;
        Map<Severity, Integer> incidentsBySeverity = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            incidentsBySeverity.put(s, 0);
        }

        for (InlineHintModel hint : hints) {
            if (hint.getEffort() != null) {
                totalStoryPoints += hint.getEffort().getStoryPoints();
            }
            Severity sev = hint.getSeverity() != null ? hint.getSeverity() : Severity.INFORMATION;
            incidentsBySeverity.merge(sev, 1, Integer::sum);
        }

        for (ClassificationModel classification : classifications) {
            if (classification.getEffort() != null) {
                totalStoryPoints += classification.getEffort().getStoryPoints();
            }
            Severity sev = classification.getSeverity() != null ? classification.getSeverity() : Severity.INFORMATION;
            incidentsBySeverity.merge(sev, 1, Integer::sum);
        }

        return new ReportSummary(
                totalFiles,
                totalIncidents,
                totalStoryPoints,
                Collections.unmodifiableMap(incidentsBySeverity),
                List.copyOf(tags)
        );
    }

    /**
     * Aggregated summary of analysis results for report generation.
     */
    public static final class ReportSummary {

        private final int totalFiles;
        private final int totalIncidents;
        private final int totalStoryPoints;
        private final Map<Severity, Integer> incidentsBySeverity;
        private final List<TechnologyTagModel> technologyTags;

        public ReportSummary(int totalFiles, int totalIncidents, int totalStoryPoints,
                             Map<Severity, Integer> incidentsBySeverity,
                             List<TechnologyTagModel> technologyTags) {
            this.totalFiles = totalFiles;
            this.totalIncidents = totalIncidents;
            this.totalStoryPoints = totalStoryPoints;
            this.incidentsBySeverity = incidentsBySeverity;
            this.technologyTags = technologyTags;
        }

        public int getTotalFiles() {
            return totalFiles;
        }

        public int getTotalIncidents() {
            return totalIncidents;
        }

        public int getTotalStoryPoints() {
            return totalStoryPoints;
        }

        public Map<Severity, Integer> getIncidentsBySeverity() {
            return incidentsBySeverity;
        }

        public List<TechnologyTagModel> getTechnologyTags() {
            return technologyTags;
        }

        @Override
        public String toString() {
            return "ReportSummary{" +
                    "totalFiles=" + totalFiles +
                    ", totalIncidents=" + totalIncidents +
                    ", totalStoryPoints=" + totalStoryPoints +
                    ", incidentsBySeverity=" + incidentsBySeverity +
                    ", technologyTags=" + technologyTags.size() +
                    '}';
        }
    }
}

package org.jboss.windup.engine;

import java.nio.file.Path;
import java.util.*;

public final class AnalysisConfiguration {

    private final List<Path> inputPaths;
    private final Path outputDirectory;
    private final Set<Technology> sourceTechnologies;
    private final Set<Technology> targetTechnologies;
    private final List<String> includePackages;
    private final List<String> excludePackages;
    private final List<Path> userRulesPaths;
    private final Map<String, Object> options;
    private final boolean sourceMode;
    private final boolean onlineMode;
    private final boolean exportCSV;
    private final boolean exportZipReport;

    private AnalysisConfiguration(Builder builder) {
        this.inputPaths = List.copyOf(builder.inputPaths);
        this.outputDirectory = builder.outputDirectory;
        this.sourceTechnologies = Set.copyOf(builder.sourceTechnologies);
        this.targetTechnologies = Set.copyOf(builder.targetTechnologies);
        this.includePackages = List.copyOf(builder.includePackages);
        this.excludePackages = List.copyOf(builder.excludePackages);
        this.userRulesPaths = List.copyOf(builder.userRulesPaths);
        this.options = Map.copyOf(builder.options);
        this.sourceMode = builder.sourceMode;
        this.onlineMode = builder.onlineMode;
        this.exportCSV = builder.exportCSV;
        this.exportZipReport = builder.exportZipReport;
    }

    public static Builder builder() { return new Builder(); }

    public List<Path> getInputPaths() { return inputPaths; }
    public Path getOutputDirectory() { return outputDirectory; }
    public Set<Technology> getSourceTechnologies() { return sourceTechnologies; }
    public Set<Technology> getTargetTechnologies() { return targetTechnologies; }
    public List<String> getIncludePackages() { return includePackages; }
    public List<String> getExcludePackages() { return excludePackages; }
    public List<Path> getUserRulesPaths() { return userRulesPaths; }
    public Map<String, Object> getOptions() { return options; }
    public boolean isSourceMode() { return sourceMode; }
    public boolean isOnlineMode() { return onlineMode; }
    public boolean isExportCSV() { return exportCSV; }
    public boolean isExportZipReport() { return exportZipReport; }

    public static final class Builder {
        private final List<Path> inputPaths = new ArrayList<>();
        private Path outputDirectory;
        private final Set<Technology> sourceTechnologies = new LinkedHashSet<>();
        private final Set<Technology> targetTechnologies = new LinkedHashSet<>();
        private final List<String> includePackages = new ArrayList<>();
        private final List<String> excludePackages = new ArrayList<>();
        private final List<Path> userRulesPaths = new ArrayList<>();
        private final Map<String, Object> options = new LinkedHashMap<>();
        private boolean sourceMode;
        private boolean onlineMode;
        private boolean exportCSV;
        private boolean exportZipReport;

        private Builder() {}

        public Builder inputPath(Path path) { inputPaths.add(path); return this; }
        public Builder outputDirectory(Path dir) { this.outputDirectory = dir; return this; }
        public Builder sourceTechnology(String id) { sourceTechnologies.add(new Technology(id)); return this; }
        public Builder targetTechnology(String id) { targetTechnologies.add(new Technology(id)); return this; }
        public Builder includePackage(String pkg) { includePackages.add(pkg); return this; }
        public Builder excludePackage(String pkg) { excludePackages.add(pkg); return this; }
        public Builder userRulesPath(Path path) { userRulesPaths.add(path); return this; }
        public Builder option(String key, Object value) { options.put(key, value); return this; }
        public Builder sourceMode(boolean val) { this.sourceMode = val; return this; }
        public Builder onlineMode(boolean val) { this.onlineMode = val; return this; }
        public Builder exportCSV(boolean val) { this.exportCSV = val; return this; }
        public Builder exportZipReport(boolean val) { this.exportZipReport = val; return this; }

        public AnalysisConfiguration build() {
            Objects.requireNonNull(outputDirectory, "outputDirectory is required");
            if (inputPaths.isEmpty()) throw new IllegalStateException("At least one input path is required");
            return new AnalysisConfiguration(this);
        }
    }
}

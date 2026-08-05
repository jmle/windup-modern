package org.jboss.windup.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class DependencyLabeler {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyLabeler.class);

    private final List<Pattern> openSourcePatterns;
    private final Set<String> excludePackages;

    public DependencyLabeler() {
        this(List.of(), Set.of());
    }

    public DependencyLabeler(List<Pattern> openSourcePatterns, Set<String> excludePackages) {
        this.openSourcePatterns = openSourcePatterns;
        this.excludePackages = excludePackages;
    }

    public static DependencyLabeler fromConfig(String labelsFilePath, List<String> excludePkgs) {
        List<Pattern> patterns = new ArrayList<>();
        if (labelsFilePath != null && !labelsFilePath.isEmpty()) {
            Path labelsFile = Path.of(labelsFilePath);
            if (Files.exists(labelsFile)) {
                try {
                    List<String> lines = Files.readAllLines(labelsFile);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            patterns.add(Pattern.compile(trimmed));
                        }
                    }
                    LOG.info("Loaded {} open-source label patterns from {}", patterns.size(), labelsFile);
                } catch (IOException e) {
                    LOG.warn("Failed to load labels file: {}", labelsFile, e);
                }
            }
        }

        Set<String> excludes = excludePkgs != null ? new HashSet<>(excludePkgs) : Set.of();
        return new DependencyLabeler(patterns, excludes);
    }

    public Map<String, String> getLabels(BuildTool.ResolvedDependency dep) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("konveyor.io/language", "java");

        String depCoord = dep.groupId() + ":" + dep.artifactId();
        if (isExcluded(dep)) {
            labels.put("konveyor.io/exclude", "true");
        }

        if (isOpenSource(depCoord)) {
            labels.put("konveyor.io/dep-source", "open-source");
        } else {
            labels.put("konveyor.io/dep-source", "internal");
        }

        return labels;
    }

    private boolean isOpenSource(String depCoord) {
        if (openSourcePatterns.isEmpty()) {
            return true;
        }
        for (Pattern pattern : openSourcePatterns) {
            if (pattern.matcher(depCoord).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcluded(BuildTool.ResolvedDependency dep) {
        if (excludePackages.isEmpty()) return false;
        String groupId = dep.groupId();
        for (String exclude : excludePackages) {
            if (groupId.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }
}

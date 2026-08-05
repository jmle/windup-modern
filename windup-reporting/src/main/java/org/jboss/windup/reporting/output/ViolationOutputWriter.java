package org.jboss.windup.reporting.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.model.LinkModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Writes analysis results as a Konveyor-format {@code output.yaml} file.
 *
 * <p>The output is a YAML array of {@link RuleSetViolation} objects, each
 * containing a map of violations keyed by rule ID.</p>
 */
@ApplicationScoped
public class ViolationOutputWriter {

    private static final Logger LOG = Logger.getLogger(ViolationOutputWriter.class.getName());
    private static final String OUTPUT_FILENAME = "output.yaml";

    private final ObjectMapper yamlMapper;

    public ViolationOutputWriter() {
        YAMLFactory factory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.yamlMapper = new ObjectMapper(factory)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
    }

    public Path write(AnalysisContext context, Path outputDirectory) throws IOException {
        ModelRegistry<InlineHintModel> hintRegistry = context.getOrCreateRegistry(InlineHintModel.class);
        List<InlineHintModel> hints = hintRegistry.findAll();

        List<RuleSetViolation> ruleSetViolations = buildOutput(hints);

        Path outputFile = outputDirectory.resolve(OUTPUT_FILENAME);
        yamlMapper.writeValue(outputFile.toFile(), ruleSetViolations);

        LOG.info(() -> String.format("Wrote %d ruleset(s) with violations to %s",
                ruleSetViolations.stream()
                        .filter(rs -> rs.getViolations() != null)
                        .mapToInt(rs -> rs.getViolations().size())
                        .sum(),
                outputFile));

        return outputFile;
    }

    List<RuleSetViolation> buildOutput(List<InlineHintModel> hints) {
        Map<String, List<InlineHintModel>> byRuleSet = hints.stream()
                .collect(Collectors.groupingBy(
                        h -> extractRuleSetName(h.getRuleId()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<RuleSetViolation> result = new ArrayList<>();

        for (var entry : byRuleSet.entrySet()) {
            String ruleSetName = entry.getKey();
            List<InlineHintModel> ruleSetHints = entry.getValue();

            RuleSetViolation rsv = new RuleSetViolation();
            rsv.setName(ruleSetName);

            Map<String, Violation> violations = buildViolations(ruleSetHints);
            rsv.setViolations(violations);

            result.add(rsv);
        }

        return result;
    }

    private Map<String, Violation> buildViolations(List<InlineHintModel> hints) {
        Map<String, List<InlineHintModel>> byRule = hints.stream()
                .collect(Collectors.groupingBy(
                        h -> extractRuleId(h.getRuleId()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Violation> violations = new LinkedHashMap<>();

        for (var entry : byRule.entrySet()) {
            String ruleId = entry.getKey();
            List<InlineHintModel> ruleHints = entry.getValue();
            InlineHintModel representative = ruleHints.get(0);

            Violation violation = new Violation();
            violation.setDescription(representative.getTitle());
            violation.setCategory(representative.getCategory());
            violation.setEffort(representative.getEffortPoints() > 0
                    ? representative.getEffortPoints() : null);

            List<Incident> incidents = ruleHints.stream()
                    .sorted(Comparator
                            .comparing((InlineHintModel h) ->
                                    h.getSourceFile() != null
                                            ? h.getSourceFile().getFilePath().toString() : "")
                            .thenComparingInt(InlineHintModel::getLineNumber))
                    .map(this::toIncident)
                    .toList();
            violation.setIncidents(incidents);

            List<Link> links = representative.getLinks().stream()
                    .map(l -> new Link(l.url(), l.title()))
                    .toList();
            if (!links.isEmpty()) {
                violation.setLinks(links);
            }

            violations.put(ruleId, violation);
        }

        return violations;
    }

    private Incident toIncident(InlineHintModel hint) {
        Incident incident = new Incident();

        if (hint.getSourceFile() != null) {
            incident.setUri("file://" + hint.getSourceFile().getFilePath().toAbsolutePath());
        }
        incident.setMessage(hint.getHint());
        if (hint.getLineNumber() > 0) {
            incident.setLineNumber(hint.getLineNumber());
        }

        return incident;
    }

    static String extractRuleSetName(String fullRuleId) {
        if (fullRuleId == null) return "unknown";
        int dot = fullRuleId.indexOf('.');
        return dot > 0 ? fullRuleId.substring(0, dot) : fullRuleId;
    }

    static String extractRuleId(String fullRuleId) {
        if (fullRuleId == null) return "unknown";
        int dot = fullRuleId.indexOf('.');
        return dot > 0 ? fullRuleId.substring(dot + 1) : fullRuleId;
    }
}

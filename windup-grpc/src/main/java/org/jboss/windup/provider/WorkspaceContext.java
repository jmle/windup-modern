package org.jboss.windup.provider;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.jboss.windup.provider.grpc.*;
import org.jboss.windup.provider.index.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class WorkspaceContext {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceContext.class);

    private final long id;
    private final String location;
    private final String analysisMode;
    private final Config config;
    private final int contextLines;
    private final SymbolIndex symbolIndex = new SymbolIndex();

    public WorkspaceContext(long id, String location, String analysisMode, Config config, int contextLines) {
        this.id = id;
        this.location = location;
        this.analysisMode = analysisMode;
        this.config = config;
        this.contextLines = contextLines;
    }

    public void index() throws IOException {
        Path root = Path.of(location);
        symbolIndex.indexDirectory(root);
        LOG.info("Workspace {} indexed: {} symbols", id, symbolIndex.size());
    }

    public SymbolIndex getSymbolIndex() {
        return symbolIndex;
    }

    public ProviderEvaluateResponse evaluate(String cap, String conditionInfo) {
        if ("referenced".equals(cap)) {
            return evaluateReferenced(conditionInfo);
        }
        LOG.warn("Unknown capability: {}", cap);
        return ProviderEvaluateResponse.newBuilder()
                .setMatched(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
        Yaml yaml = new Yaml();
        Map<String, Object> cond = yaml.load(conditionInfo);
        Map<String, Object> referenced = (Map<String, Object>) cond.get("referenced");

        if (referenced == null) {
            return ProviderEvaluateResponse.newBuilder().setMatched(false).build();
        }

        String pattern = (String) referenced.get("pattern");
        String locationStr = (String) referenced.getOrDefault("location", "");
        LocationType location = LocationType.fromString(locationStr);

        LOG.debug("Evaluate referenced: pattern={} location={}", pattern, location);

        // Handle annotated sub-condition
        String annotatedPattern = null;
        Map<String, String> annotatedElements = null;
        Map<String, Object> annotated = (Map<String, Object>) referenced.get("annotated");
        if (annotated != null) {
            annotatedPattern = (String) annotated.get("pattern");
            List<Map<String, String>> elements = (List<Map<String, String>>) annotated.get("elements");
            if (elements != null && !elements.isEmpty()) {
                annotatedElements = new LinkedHashMap<>();
                for (Map<String, String> elem : elements) {
                    annotatedElements.put(elem.get("name"), elem.get("value"));
                }
            }
        }

        // Handle METHOD with "* ReturnType" pattern — query RETURN_TYPE location
        List<IndexedSymbol> matches;
        if ((location == LocationType.METHOD) && pattern.contains(" ")) {
            matches = queryMethodWithReturnType(pattern);
        } else if (annotatedPattern != null || annotatedElements != null) {
            matches = queryAnnotated(pattern, location, annotatedPattern, annotatedElements);
        } else {
            matches = symbolIndex.query(pattern, location);
        }

        if (matches.isEmpty()) {
            return ProviderEvaluateResponse.newBuilder().setMatched(false).build();
        }

        // Filter: for IMPORT location, only keep Module kind
        if (location == LocationType.IMPORT) {
            matches = matches.stream()
                    .filter(s -> s.kind() == SymbolKind.MODULE)
                    .toList();
        }

        if (matches.isEmpty()) {
            return ProviderEvaluateResponse.newBuilder().setMatched(false).build();
        }

        // Build incidents
        ProviderEvaluateResponse.Builder response = ProviderEvaluateResponse.newBuilder()
                .setMatched(true);

        for (IndexedSymbol sym : matches) {
            IncidentContext.Builder incident = IncidentContext.newBuilder()
                    .setFileURI(sym.fileUri())
                    .setLineNumber(sym.line() + 1);

            Struct.Builder vars = Struct.newBuilder()
                    .putFields("kind", Value.newBuilder().setStringValue(sym.kind().label()).build())
                    .putFields("name", Value.newBuilder().setStringValue(sym.name()).build())
                    .putFields("file", Value.newBuilder().setStringValue(sym.fileUri()).build())
                    .putFields("package", Value.newBuilder().setStringValue(sym.packageName()).build());

            incident.setVariables(vars);

            // Code location
            if (!(sym.line() == 0 && sym.startChar() == 0 && sym.endLine() == 0 && sym.endChar() == 0)) {
                Location.Builder codeLoc = Location.newBuilder()
                        .setStartPosition(Position.newBuilder()
                                .setLine(sym.line())
                                .setCharacter(sym.startChar()))
                        .setEndPosition(Position.newBuilder()
                                .setLine(sym.endLine())
                                .setCharacter(sym.endChar()));
                incident.setCodeLocation(codeLoc);
            }

            response.addIncidentContexts(incident);
        }

        return response.build();
    }

    private List<IndexedSymbol> queryMethodWithReturnType(String pattern) {
        int spaceIdx = pattern.indexOf(' ');
        String namePattern = pattern.substring(0, spaceIdx).trim();
        String typePattern = pattern.substring(spaceIdx + 1).trim();

        List<IndexedSymbol> returnTypeMatches = symbolIndex.query(typePattern, LocationType.RETURN_TYPE);
        if ("*".equals(namePattern)) {
            return returnTypeMatches;
        }

        var nameRegex = SymbolIndex.globToRegex(namePattern);
        return returnTypeMatches.stream()
                .filter(s -> nameRegex.matcher(s.name()).matches())
                .toList();
    }

    private List<IndexedSymbol> queryAnnotated(String pattern, LocationType location,
                                                String annotatedPattern,
                                                Map<String, String> annotatedElements) {
        List<IndexedSymbol> baseMatches = symbolIndex.query(pattern, location);
        List<IndexedSymbol> result = new ArrayList<>();
        for (IndexedSymbol sym : baseMatches) {
            if (symbolIndex.hasMatchingAnnotation(sym, annotatedPattern, annotatedElements)) {
                result.add(sym);
            }
        }
        return result;
    }

    public DependencyResponse getDependencies() {
        DependencyParser parser = new DependencyParser();
        List<DependencyParser.ParsedDependency> deps = parser.parseDirectory(Path.of(location));

        Map<String, List<DependencyParser.ParsedDependency>> byFile = new LinkedHashMap<>();
        for (DependencyParser.ParsedDependency dep : deps) {
            byFile.computeIfAbsent(dep.fileUri(), k -> new ArrayList<>()).add(dep);
        }

        DependencyResponse.Builder response = DependencyResponse.newBuilder()
                .setSuccessful(true);

        for (Map.Entry<String, List<DependencyParser.ParsedDependency>> entry : byFile.entrySet()) {
            DependencyList.Builder depList = DependencyList.newBuilder();
            for (DependencyParser.ParsedDependency dep : entry.getValue()) {
                Dependency.Builder d = Dependency.newBuilder()
                        .setName(dep.name())
                        .setVersion(dep.version() != null ? dep.version() : "");
                if (dep.classifier() != null) {
                    d.setClassifier(dep.classifier());
                }
                depList.addDeps(d);
            }
            response.addFileDep(FileDep.newBuilder()
                    .setFileURI(entry.getKey())
                    .setList(depList));
        }

        LOG.info("Parsed {} dependencies from {}", deps.size(), location);
        return response.build();
    }
}

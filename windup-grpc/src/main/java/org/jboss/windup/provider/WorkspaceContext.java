package org.jboss.windup.provider;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.jboss.windup.provider.buildtool.BuildTool;
import org.jboss.windup.provider.buildtool.BuildToolDetector;
import org.jboss.windup.provider.buildtool.DependencyLabeler;
import org.jboss.windup.provider.buildtool.DependencyResolver;
import org.jboss.windup.provider.grpc.*;
import org.jboss.windup.provider.index.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Holds the analysis state for a single initialized workspace. On {@link #index()}, it parses
 * all Java source files into a {@link SymbolIndex}, detects the build tool, resolves
 * dependencies (downloading sources and decompiling when needed), and indexes dependency
 * symbols. Subsequent {@link #evaluate} calls query this index against rule conditions.
 */
public class WorkspaceContext {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceContext.class);

    private final long id;
    private final String location;
    private final String analysisMode;
    private final Config config;
    private final int contextLines;
    private final SymbolIndex symbolIndex = new SymbolIndex();
    private BuildTool buildTool;
    private List<BuildTool.ResolvedDependency> resolvedDeps;

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
        LOG.info("Workspace {} indexed: {} symbols from application source", id, symbolIndex.size());

        buildTool = BuildToolDetector.detect(root);
        try {
            resolvedDeps = buildTool.getDependencies(root);
            LOG.info("Resolved {} dependencies via {} for workspace {}",
                    resolvedDeps.size(), buildTool.getType(), id);
        } catch (Exception e) {
            LOG.warn("Dependency resolution failed, falling back to static parsing: {}", e.getMessage());
            resolvedDeps = List.of();
        }

        if (!resolvedDeps.isEmpty() && !"source-only".equals(analysisMode)) {
            resolveDependencySources(root);
        }
    }

    private void resolveDependencySources(Path projectDir) {
        DependencyResolver resolver = new DependencyResolver();

        if (buildTool.getType() == BuildTool.Type.MAVEN) {
            resolver.downloadSources(resolvedDeps, projectDir);
            resolvedDeps = buildTool.getDependencies(projectDir);
        }

        Path workDir = projectDir.resolve(".windup-work");
        try {
            DependencyResolver.ResolveResult result = resolver.resolve(resolvedDeps, workDir);
            if (!result.sourceDirs().isEmpty()) {
                indexDependencySources(result.sourceDirs());
                LOG.info("Indexed {} dependency source directories ({} decompiled)",
                        result.sourceDirs().size(), result.decompiledCount());
            }
        } catch (Exception e) {
            LOG.warn("Dependency source resolution failed: {}", e.getMessage());
        }
    }

    public void indexDependencySources(List<Path> sourceDirs) throws IOException {
        for (Path dir : sourceDirs) {
            if (Files.isDirectory(dir)) {
                symbolIndex.indexDependencyDirectory(dir);
            }
        }
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
                    .setLineNumber(sym.line() + 1)
                    .setIsDependencyIncident(symbolIndex.isDependencyFile(sym.fileUri()));

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
        if (resolvedDeps != null && !resolvedDeps.isEmpty()) {
            return getDependenciesFromBuildTool();
        }
        return getDependenciesFromStaticParsing();
    }

    private DependencyResponse getDependenciesFromBuildTool() {
        String buildFileUri = Path.of(location, "pom.xml").toUri().toString();
        DependencyLabeler labeler = new DependencyLabeler();

        DependencyList.Builder depList = DependencyList.newBuilder();
        for (BuildTool.ResolvedDependency dep : resolvedDeps) {
            Dependency.Builder d = Dependency.newBuilder()
                    .setName(dep.name())
                    .setVersion(dep.version() != null ? dep.version() : "");
            if (dep.classifier() != null) {
                d.setClassifier(dep.classifier());
            }

            Map<String, String> labels = labeler.getLabels(dep);
            for (Map.Entry<String, String> label : labels.entrySet()) {
                d.addLabels(label.getKey() + "=" + label.getValue());
            }

            depList.addDeps(d);
        }

        DependencyResponse.Builder response = DependencyResponse.newBuilder()
                .setSuccessful(true)
                .addFileDep(FileDep.newBuilder()
                        .setFileURI(buildFileUri)
                        .setList(depList));

        LOG.info("Returning {} resolved dependencies from {}", resolvedDeps.size(), buildTool.getType());
        return response.build();
    }

    private DependencyResponse getDependenciesFromStaticParsing() {
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

        LOG.info("Parsed {} dependencies from static analysis of {}", deps.size(), location);
        return response.build();
    }

    public BuildTool getBuildTool() {
        return buildTool;
    }

    public List<BuildTool.ResolvedDependency> getResolvedDeps() {
        return resolvedDeps != null ? resolvedDeps : List.of();
    }
}

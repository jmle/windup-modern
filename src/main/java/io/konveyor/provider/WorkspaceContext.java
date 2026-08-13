package io.konveyor.provider;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.konveyor.provider.buildtool.BuildTool;
import io.konveyor.provider.buildtool.BuildToolDetector;
import io.konveyor.provider.buildtool.DependencyCache;
import io.konveyor.provider.buildtool.DependencyLabeler;
import io.konveyor.provider.buildtool.DependencyResolver;
import io.konveyor.provider.grpc.*;
import io.konveyor.provider.index.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

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
    private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(Yaml::new);
    private final SymbolIndex symbolIndex = new SymbolIndex();
    private final List<String> includedPaths;
    private final DependencyLabeler labeler;
    private BuildTool buildTool;
    private List<BuildTool.ResolvedDependency> resolvedDeps;
    private List<BuildTool.DagEntry> resolvedDag;

    public WorkspaceContext(long id, String location, String analysisMode, Config config, int contextLines) {
        this.id = id;
        this.location = location;
        this.analysisMode = analysisMode;
        this.config = config;
        this.contextLines = contextLines;
        this.includedPaths = extractIncludedPaths(config);
        this.labeler = createLabeler(config);
    }

    private static List<String> extractIncludedPaths(Config config) {
        return extractListConfig(config, "includedPaths");
    }

    private static DependencyLabeler createLabeler(Config config) {
        String labelsFile = extractStringConfig(config, "depOpenSourceLabelsFile");
        List<String> excludePkgs = extractListConfig(config, "excludePackages");
        return DependencyLabeler.fromConfig(labelsFile, excludePkgs);
    }

    static String extractStringConfig(Config config, String key) {
        if (!config.hasProviderSpecificConfig()) return null;
        var fields = config.getProviderSpecificConfig().getFieldsMap();
        var value = fields.get(key);
        if (value == null || !value.hasStringValue()) return null;
        String s = value.getStringValue();
        return s.isEmpty() ? null : s;
    }

    static List<String> extractListConfig(Config config, String key) {
        if (!config.hasProviderSpecificConfig()) return List.of();
        var fields = config.getProviderSpecificConfig().getFieldsMap();
        var value = fields.get(key);
        if (value == null || !value.hasListValue()) return List.of();
        return value.getListValue().getValuesList().stream()
                .filter(com.google.protobuf.Value::hasStringValue)
                .map(com.google.protobuf.Value::getStringValue)
                .toList();
    }

    public void index() throws IOException {
        Path root = Path.of(location);

        if (isArchive(root)) {
            indexArchive(root);
        } else {
            if (includedPaths.isEmpty()) {
                symbolIndex.indexDirectory(root);
            } else {
                symbolIndex.indexDirectory(root, includedPaths);
            }
        }
        LOG.info("Workspace {} indexed: {} symbols from application source", id, symbolIndex.size());

        if (!isArchive(root)) {
            String mavenIndexPathStr = extractStringConfig(config, "mavenIndexPath");
            Path mavenIndexPath = mavenIndexPathStr != null ? Path.of(mavenIndexPathStr) : null;
            buildTool = BuildToolDetector.detect(root, mavenIndexPath);
            try {
                Path buildFile = detectBuildFile(root);
                DependencyCache cache = buildFile != null ? new DependencyCache(buildFile) : null;
                if (cache != null && cache.isValid()) {
                    resolvedDag = cache.getCached();
                    resolvedDeps = BuildTool.flattenDag(resolvedDag);
                    LOG.info("Using cached dependencies ({} top-level) for workspace {}", resolvedDag.size(), id);
                } else {
                    resolvedDag = buildTool.getDependenciesDAG(root);
                    resolvedDeps = BuildTool.flattenDag(resolvedDag);
                    if (cache != null) cache.store(resolvedDag);
                    LOG.info("Resolved {} dependencies ({} top-level) via {} for workspace {}",
                            resolvedDeps.size(), resolvedDag.size(), buildTool.getType(), id);
                }
            } catch (Exception e) {
                LOG.warn("Dependency resolution failed, falling back to static parsing: {}", e.getMessage());
                resolvedDag = List.of();
                resolvedDeps = List.of();
            }

            if (!resolvedDeps.isEmpty() && !"source-only".equals(analysisMode)) {
                resolveDependencySources(root);
            }
        }
    }

    private static Path detectBuildFile(Path root) {
        Path pom = root.resolve("pom.xml");
        if (Files.exists(pom)) return pom;
        Path gradle = root.resolve("build.gradle");
        if (Files.exists(gradle)) return gradle;
        Path gradleKts = root.resolve("build.gradle.kts");
        if (Files.exists(gradleKts)) return gradleKts;
        return null;
    }

    private static boolean isArchive(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
    }

    private void indexArchive(Path archivePath) throws IOException {
        Path workDir = archivePath.getParent().resolve(".java-provider-work");
        Files.createDirectories(workDir);

        var decompiler = new io.konveyor.provider.decompiler.VineflowerDecompiler();
        var handler = new io.konveyor.provider.decompiler.ArchiveHandler(decompiler);
        var result = handler.handleArchive(archivePath, workDir);

        for (Path sourceDir : result.sourceDirs()) {
            if (Files.isDirectory(sourceDir)) {
                symbolIndex.indexDirectory(sourceDir);
            }
        }
    }

    private void resolveDependencySources(Path projectDir) {
        DependencyResolver resolver = new DependencyResolver();

        if (buildTool.getType() == BuildTool.Type.MAVEN) {
            resolver.downloadSources(resolvedDeps, projectDir);
            resolvedDag = buildTool.getDependenciesDAG(projectDir);
            resolvedDeps = BuildTool.flattenDag(resolvedDag);
        }

        // Place work dir outside the source tree so the engine's file-discovery rules
        // don't pick up decompiled dependency sources as application files
        Path workDir = Path.of(System.getProperty("user.home"), ".java-provider-work", String.valueOf(id));
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
        if ("dependency".equals(cap)) {
            return evaluateDependency(conditionInfo);
        }
        LOG.warn("Unknown capability: {}", cap);
        return ProviderEvaluateResponse.newBuilder()
                .setMatched(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
        Map<String, Object> cond = YAML.get().load(conditionInfo);
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
            List<Map<String, Object>> elements = (List<Map<String, Object>>) annotated.get("elements");
            if (elements != null && !elements.isEmpty()) {
                annotatedElements = new LinkedHashMap<>();
                for (Map<String, Object> elem : elements) {
                    annotatedElements.put(String.valueOf(elem.get("name")),
                            String.valueOf(elem.get("value")));
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

        List<String> filepaths = extractFilepaths(referenced);
        if (filepaths != null && !filepaths.isEmpty()) {
            Set<String> allowedUris = new HashSet<>();
            for (String fp : filepaths) {
                try {
                    Path path = fp.startsWith("file://") ? Path.of(URI.create(fp)) : Path.of(fp);
                    allowedUris.add(path.toUri().toString());
                } catch (Exception e) {
                    allowedUris.add(fp);
                }
            }
            matches = matches.stream()
                    .filter(s -> allowedUris.contains(s.fileUri()))
                    .toList();
        }

        // Exclude matches from dependency source files — only application source should produce incidents
        matches = matches.stream()
                .filter(s -> !symbolIndex.isDependencyFile(s.fileUri()))
                .toList();

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

    @SuppressWarnings("unchecked")
    private static List<String> extractFilepaths(Map<String, Object> referenced) {
        Object fpObj = referenced.get("filepaths");
        if (fpObj == null) return null;

        List<String> result = new ArrayList<>();
        if (fpObj instanceof List<?> fpList) {
            for (Object item : fpList) {
                if (item instanceof String s) {
                    Collections.addAll(result, s.split("\\s+"));
                }
            }
        } else if (fpObj instanceof String s) {
            Collections.addAll(result, s.split("\\s+"));
        }
        result.removeIf(String::isEmpty);
        return result.isEmpty() ? null : result;
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

    @SuppressWarnings("unchecked")
    private ProviderEvaluateResponse evaluateDependency(String conditionInfo) {
        Map<String, Object> cond = YAML.get().load(conditionInfo);
        Map<String, Object> depCond = (Map<String, Object>) cond.get("dependency");

        if (depCond == null || resolvedDeps == null || resolvedDeps.isEmpty()) {
            return ProviderEvaluateResponse.newBuilder().setMatched(false).build();
        }

        String nameExact = (String) depCond.get("name");
        String nameRegex = (String) depCond.get("name_regex");
        String lowerbound = (String) depCond.get("lowerbound");
        String upperbound = (String) depCond.get("upperbound");

        Pattern namePattern = null;
        if (nameRegex != null) {
            namePattern = Pattern.compile(nameRegex);
        }

        String buildFile = buildTool.getType() == BuildTool.Type.GRADLE
                ? "build.gradle" : "pom.xml";
        Path buildFilePath = Path.of(location, buildFile);
        String buildFileUri = buildFilePath.toUri().toString();

        List<String> buildFileLines;
        try {
            buildFileLines = Files.readAllLines(buildFilePath);
        } catch (IOException e) {
            buildFileLines = List.of();
        }

        ProviderEvaluateResponse.Builder response = ProviderEvaluateResponse.newBuilder();
        List<IncidentContext> incidents = new ArrayList<>();

        for (BuildTool.ResolvedDependency dep : resolvedDeps) {
            if (!matchesName(dep, nameExact, namePattern)) continue;
            if (!matchesVersionBounds(dep.version(), lowerbound, upperbound)) continue;

            int lineNumber = 0;
            if (!buildFileLines.isEmpty()) {
                int line0 = "pom.xml".equals(buildFile)
                        ? DependencyLocationService.findInPom(buildFileLines, dep.groupId(), dep.artifactId())
                        : DependencyLocationService.findInGradle(buildFileLines, dep.groupId(), dep.artifactId());
                lineNumber = line0 + 1;
            }

            Struct.Builder vars = Struct.newBuilder()
                    .putFields("name", Value.newBuilder().setStringValue(dep.name()).build())
                    .putFields("version", Value.newBuilder().setStringValue(
                            dep.version() != null ? dep.version() : "").build())
                    .putFields("groupId", Value.newBuilder().setStringValue(dep.groupId()).build())
                    .putFields("artifactId", Value.newBuilder().setStringValue(dep.artifactId()).build());

            IncidentContext.Builder incident = IncidentContext.newBuilder()
                    .setFileURI(buildFileUri)
                    .setLineNumber(lineNumber)
                    .setVariables(vars);

            incidents.add(incident.build());
        }

        if (incidents.isEmpty()) {
            return response.setMatched(false).build();
        }

        response.setMatched(true);
        incidents.forEach(response::addIncidentContexts);
        return response.build();
    }

    private boolean matchesName(BuildTool.ResolvedDependency dep, String nameExact, Pattern namePattern) {
        String depName = dep.name();
        if (nameExact != null) {
            return depName.equals(nameExact);
        }
        if (namePattern != null) {
            return namePattern.matcher(depName).matches();
        }
        return true;
    }

    private boolean matchesVersionBounds(String version, String lowerbound, String upperbound) {
        if (version == null || version.isEmpty()) return true;
        if (lowerbound == null && upperbound == null) return true;

        ComparableVersion depVersion = new ComparableVersion(version);

        if (lowerbound != null) {
            ComparableVersion lower = new ComparableVersion(lowerbound);
            if (depVersion.compareTo(lower) < 0) return false;
        }
        if (upperbound != null) {
            ComparableVersion upper = new ComparableVersion(upperbound);
            if (depVersion.compareTo(upper) > 0) return false;
        }
        return true;
    }

    public DependencyResponse getDependencies() {
        if (resolvedDeps != null && !resolvedDeps.isEmpty()) {
            return getDependenciesFromBuildTool();
        }
        return getDependenciesFromStaticParsing();
    }

    private DependencyResponse getDependenciesFromBuildTool() {
        String buildFileUri = Path.of(location, "pom.xml").toUri().toString();

        DependencyList.Builder depList = DependencyList.newBuilder();
        for (BuildTool.ResolvedDependency dep : resolvedDeps) {
            depList.addDeps(toProtoDependency(dep));
        }

        DependencyResponse.Builder response = DependencyResponse.newBuilder()
                .setSuccessful(true)
                .addFileDep(FileDep.newBuilder()
                        .setFileURI(buildFileUri)
                        .setList(depList));

        LOG.info("Returning {} resolved dependencies from {}", resolvedDeps.size(), buildTool.getType());
        return response.build();
    }

    public DependencyDAGResponse getDependenciesDAG() {
        if (resolvedDag == null || resolvedDag.isEmpty()) {
            return DependencyDAGResponse.newBuilder()
                    .setSuccessful(true)
                    .build();
        }

        String buildFileUri = Path.of(location, "pom.xml").toUri().toString();

        FileDAGDep.Builder fileDag = FileDAGDep.newBuilder()
                .setFileURI(buildFileUri);

        for (BuildTool.DagEntry entry : resolvedDag) {
            fileDag.addList(toProtoDagItem(entry));
        }

        return DependencyDAGResponse.newBuilder()
                .setSuccessful(true)
                .addFileDagDep(fileDag)
                .build();
    }

    private DependencyDAGItem toProtoDagItem(BuildTool.DagEntry entry) {
        DependencyDAGItem.Builder item = DependencyDAGItem.newBuilder()
                .setKey(toProtoDependency(entry.dep()));

        for (BuildTool.DagEntry child : entry.children()) {
            item.addAddedDeps(toProtoDagItem(child));
        }

        return item.build();
    }

    private io.konveyor.provider.grpc.Dependency toProtoDependency(BuildTool.ResolvedDependency dep) {
        io.konveyor.provider.grpc.Dependency.Builder d = io.konveyor.provider.grpc.Dependency.newBuilder()
                .setName(dep.name())
                .setVersion(dep.version() != null ? dep.version() : "")
                .setIndirect(dep.indirect());

        if (dep.classifier() != null) {
            d.setClassifier(dep.classifier());
        }

        Struct.Builder extras = Struct.newBuilder()
                .putFields("groupId", Value.newBuilder().setStringValue(dep.groupId()).build())
                .putFields("artifactId", Value.newBuilder().setStringValue(dep.artifactId()).build());

        if (dep.pomPath() != null) {
            extras.putFields("pomPath", Value.newBuilder().setStringValue(dep.pomPath()).build());
            d.setFileURIPrefix(Path.of(dep.pomPath()).toUri().toString());
        }

        d.setExtras(extras);

        Map<String, String> labels = labeler.getLabels(dep);
        for (Map.Entry<String, String> label : labels.entrySet()) {
            d.addLabels(label.getKey() + "=" + label.getValue());
        }

        return d.build();
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

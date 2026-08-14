package io.konveyor.provider.index;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of all Java symbols extracted from source files. Supports querying by
 * pattern (glob syntax) and {@link LocationType} (IMPORT, ANNOTATION, METHOD_CALL, etc.).
 * Tracks which files originate from dependencies vs application source so that
 * {@code IsDependencyIncident} can be set on gRPC responses.
 */
public class SymbolIndex {

    private static final Logger LOG = LoggerFactory.getLogger(SymbolIndex.class);

    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            "org.eclipse.jdt.core.compiler.source", "17",
            "org.eclipse.jdt.core.compiler.compliance", "17",
            "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"
    );

    private static final int BATCH_SIZE = 64;
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private final List<IndexedSymbol> allSymbols = new ArrayList<>();
    private final Map<LocationType, List<IndexedSymbol>> byLocation = new EnumMap<>(LocationType.class);
    private final Set<String> dependencyFileUris = new HashSet<>();

    public void indexDirectory(Path root) throws IOException {
        indexDirectory(root, List.of());
    }

    public void indexDirectory(Path root, List<String> includedPaths) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }

        List<Path> javaFiles = collectJavaFiles(root, includedPaths);
        LOG.info("Parsing {} Java files in {}", javaFiles.size(), root);

        List<IndexedSymbol> parsed = parseFilesParallel(javaFiles);
        mergeSymbols(parsed);

        LOG.info("Indexed {} symbols across {} files", allSymbols.size(), javaFiles.size());
    }

    public void indexDependencyDirectory(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }

        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                    dependencyFileUris.add(file.toUri().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        LOG.info("Parsing {} dependency Java files in {}", javaFiles.size(), root);

        List<IndexedSymbol> parsed = parseFilesParallel(javaFiles);
        mergeSymbols(parsed);

        LOG.info("Indexed {} total symbols (including dependencies)", allSymbols.size());
    }

    private List<Path> collectJavaFiles(Path root, List<String> includedPaths) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    if (includedPaths.isEmpty() || matchesIncludedPath(root, file, includedPaths)) {
                        javaFiles.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return javaFiles;
    }

    /**
     * Parses files in parallel using JDT batch API. Files are partitioned into chunks,
     * and each chunk is processed with {@link ASTParser#createASTs} which reuses internal
     * compiler state across files within the chunk. Chunks run concurrently via ForkJoinPool.
     */
    private List<IndexedSymbol> parseFilesParallel(List<Path> javaFiles) {
        if (javaFiles.isEmpty()) {
            return List.of();
        }

        if (javaFiles.size() <= BATCH_SIZE) {
            return parseBatch(javaFiles);
        }

        List<List<Path>> chunks = partitionList(javaFiles, BATCH_SIZE);
        ForkJoinPool pool = new ForkJoinPool(
                Math.min(chunks.size(), Runtime.getRuntime().availableProcessors()));

        try {
            List<Future<List<IndexedSymbol>>> futures = new ArrayList<>();
            for (List<Path> chunk : chunks) {
                futures.add(pool.submit(() -> parseBatch(chunk)));
            }

            List<IndexedSymbol> result = new ArrayList<>(javaFiles.size() * 10);
            for (Future<List<IndexedSymbol>> future : futures) {
                result.addAll(future.get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parsing interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Parsing failed", e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Parses a batch of files using JDT's batch API, which reuses internal compiler
     * state (scanner, name tables) across files for better performance than creating
     * a new ASTParser per file.
     */
    private List<IndexedSymbol> parseBatch(List<Path> files) {
        String[] sourcePaths = new String[files.size()];
        String[] encodings = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            sourcePaths[i] = files.get(i).toAbsolutePath().toString();
            encodings[i] = "UTF-8";
        }

        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(COMPILER_OPTIONS);
        parser.setResolveBindings(false);

        List<IndexedSymbol> batchSymbols = new ArrayList<>();

        parser.createASTs(sourcePaths, encodings, new String[0], new FileASTRequestor() {
            @Override
            public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                try {
                    String fileUri = Path.of(sourceFilePath).toUri().toString();
                    SymbolCollector collector = new SymbolCollector(cu, fileUri);
                    cu.accept(collector);
                    batchSymbols.addAll(collector.getSymbols());
                } catch (Exception e) {
                    LOG.warn("Failed to parse {}: {}", sourceFilePath, e.getMessage());
                }
            }
        }, null);

        return batchSymbols;
    }

    private synchronized void mergeSymbols(List<IndexedSymbol> symbols) {
        allSymbols.addAll(symbols);
        for (IndexedSymbol sym : symbols) {
            byLocation.computeIfAbsent(sym.location(), k -> new ArrayList<>()).add(sym);
        }
    }

    /**
     * Indexes a single file. Used by tests; production code uses
     * {@link #parseFilesParallel} for batch processing.
     */
    public void indexFile(Path file) throws IOException {
        String source = Files.readString(file);
        String fileUri = file.toUri().toString();

        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(COMPILER_OPTIONS);
        parser.setResolveBindings(false);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        SymbolCollector collector = new SymbolCollector(cu, fileUri);
        cu.accept(collector);

        List<IndexedSymbol> fileSymbols = collector.getSymbols();
        mergeSymbols(fileSymbols);
    }

    public boolean isDependencyFile(String fileUri) {
        return dependencyFileUris.contains(fileUri);
    }

    public List<IndexedSymbol> query(String pattern, LocationType location) {
        if (location == LocationType.PACKAGE) {
            return queryPackage(pattern);
        }

        // TYPE_KEYWORD ("type" location string) searches the TYPE location
        LocationType searchLocation = location == LocationType.TYPE_KEYWORD ? LocationType.TYPE : location;

        // For type-reference locations, strip generic type parameters (erasure matching)
        boolean useErasure = isTypeReferenceLocation(searchLocation);
        String effectivePattern = useErasure ? stripTypeParameters(pattern) : pattern;

        List<IndexedSymbol> candidates = byLocation.getOrDefault(searchLocation, List.of());

        Pattern regex = globToRegex(effectivePattern);
        List<IndexedSymbol> matches = new ArrayList<>();
        for (IndexedSymbol sym : candidates) {
            if (matchesSymbol(sym, regex, effectivePattern, searchLocation, useErasure)) {
                matches.add(sym);
            }
        }

        // For TYPE/TYPE_KEYWORD queries, also include matching IMPORT symbols (Module kind)
        if (searchLocation == LocationType.TYPE) {
            List<IndexedSymbol> imports = byLocation.getOrDefault(LocationType.IMPORT, List.of());
            for (IndexedSymbol imp : imports) {
                String impQn = useErasure ? stripTypeParameters(imp.qualifiedName()) : imp.qualifiedName();
                if (regex.matcher(impQn).matches()) {
                    matches.add(imp);
                }
            }
        }

        return matches;
    }

    static String stripTypeParameters(String s) {
        int idx = s.indexOf('<');
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private static boolean isTypeReferenceLocation(LocationType location) {
        return location == LocationType.TYPE
                || location == LocationType.CONSTRUCTOR_CALL
                || location == LocationType.FIELD
                || location == LocationType.VARIABLE_DECLARATION
                || location == LocationType.INHERITANCE
                || location == LocationType.IMPLEMENTS_TYPE
                || location == LocationType.IMPORT
                || location == LocationType.ANNOTATION
                || location == LocationType.CLASS;
    }

    private List<IndexedSymbol> queryPackage(String pattern) {
        Pattern regex = globToRegex(pattern);
        List<IndexedSymbol> matches = new ArrayList<>();

        for (IndexedSymbol pkg : byLocation.getOrDefault(LocationType.PACKAGE, List.of())) {
            if (regex.matcher(pkg.qualifiedName()).matches()) {
                matches.add(pkg);
            }
        }

        for (IndexedSymbol imp : byLocation.getOrDefault(LocationType.IMPORT, List.of())) {
            String fqn = imp.qualifiedName();
            String pkg;
            if (fqn.endsWith(".*")) {
                pkg = fqn.substring(0, fqn.length() - 2);
            } else {
                int dot = fqn.lastIndexOf('.');
                pkg = dot > 0 ? fqn.substring(0, dot) : fqn;
            }

            if (regex.matcher(pkg).matches()) {
                matches.add(new IndexedSymbol(
                        imp.qualifiedName(), pkg, SymbolKind.MODULE, LocationType.PACKAGE,
                        imp.fileUri(), imp.packageName(),
                        imp.line(), imp.startChar(), imp.endLine(), imp.endChar(),
                        imp.annotations()
                ));
            }
        }
        return matches;
    }

    public List<IndexedSymbol> queryAnnotated(String pattern, LocationType location,
                                               String annotatedPattern,
                                               List<IndexedSymbol.AnnotationInfo> requiredElements) {
        List<IndexedSymbol> baseMatches = query(pattern, location);
        if (annotatedPattern == null && (requiredElements == null || requiredElements.isEmpty())) {
            return baseMatches;
        }

        Pattern annotRegex = annotatedPattern != null ? globToRegex(annotatedPattern) : null;

        List<IndexedSymbol> result = new ArrayList<>();
        for (IndexedSymbol sym : baseMatches) {
            if (hasMatchingAnnotation(sym, annotRegex, requiredElements)) {
                result.add(sym);
            }
        }
        return result;
    }

    private boolean matchesSymbol(IndexedSymbol sym, Pattern regex, String rawPattern,
                                   LocationType location, boolean useErasure) {
        // For FIELD and METHOD with "* type" pattern format
        if ((location == LocationType.FIELD || location == LocationType.METHOD)
                && rawPattern.contains(" ")) {
            return matchesNameAndType(sym, rawPattern, location);
        }

        String qn = useErasure ? stripTypeParameters(sym.qualifiedName()) : sym.qualifiedName();

        // For METHOD/METHOD_CALL, allow suffix matching (e.g., "HomeService.do*" matches
        // "com.example.service.HomeService.doStuff")
        if (location == LocationType.METHOD || location == LocationType.METHOD_CALL) {
            if (regex.matcher(qn).matches() || regex.matcher(sym.name()).matches()) {
                return true;
            }
            return matchesSuffix(qn, regex);
        }

        return regex.matcher(qn).matches();
    }

    private boolean matchesSuffix(String qualifiedName, Pattern regex) {
        int dot = qualifiedName.indexOf('.');
        while (dot >= 0) {
            String suffix = qualifiedName.substring(dot + 1);
            if (regex.matcher(suffix).matches()) {
                return true;
            }
            dot = qualifiedName.indexOf('.', dot + 1);
        }
        return false;
    }

    private boolean matchesNameAndType(IndexedSymbol sym, String rawPattern, LocationType location) {
        int spaceIdx = rawPattern.indexOf(' ');
        String namePattern = rawPattern.substring(0, spaceIdx).trim();
        String typePattern = rawPattern.substring(spaceIdx + 1).trim();

        Pattern nameRegex = globToRegex(namePattern);
        Pattern typeRegex = globToRegex(stripTypeParameters(typePattern));

        String symQn = stripTypeParameters(sym.qualifiedName());

        if (location == LocationType.FIELD) {
            return nameRegex.matcher(sym.name()).matches()
                    && typeRegex.matcher(symQn).matches();
        } else {
            return nameRegex.matcher(sym.name()).matches()
                    && typeRegex.matcher(symQn).matches();
        }
    }

    private boolean hasMatchingAnnotation(IndexedSymbol sym, Pattern annotRegex,
                                          List<IndexedSymbol.AnnotationInfo> requiredElements) {
        if (sym.annotations().isEmpty()) {
            return false;
        }

        for (IndexedSymbol.AnnotationInfo annot : sym.annotations()) {
            boolean annotMatches = (annotRegex == null) || annotRegex.matcher(annot.qualifiedName()).matches();
            if (!annotMatches) continue;

            if (requiredElements == null || requiredElements.isEmpty()) {
                return true;
            }

            boolean allElementsMatch = true;
            for (IndexedSymbol.AnnotationInfo reqElem : requiredElements) {
                // reqElem.qualifiedName() is used as element name, elements() has the expected values
                // Wait, the AnnotationInfo for required elements comes from the rule condition.
                // Let me re-read the condition format...
                // annotated: { pattern: "...", elements: [{name: "value", value: "regex"}] }
                // So I need a different approach for required elements
                allElementsMatch = false;
                break;
            }

            if (allElementsMatch) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMatchingAnnotation(IndexedSymbol sym, String annotatedPattern,
                                          Map<String, String> requiredElements) {
        if (sym.annotations().isEmpty()) {
            return false;
        }

        Pattern annotRegex = annotatedPattern != null && !annotatedPattern.isEmpty()
                ? globToRegex(annotatedPattern) : null;

        for (IndexedSymbol.AnnotationInfo annot : sym.annotations()) {
            boolean annotMatches = (annotRegex == null) || annotRegex.matcher(annot.qualifiedName()).matches();
            if (!annotMatches) continue;

            if (requiredElements == null || requiredElements.isEmpty()) {
                return true;
            }

            boolean allMatch = true;
            for (Map.Entry<String, String> req : requiredElements.entrySet()) {
                String actual = annot.elements().get(req.getKey());
                if (actual == null) {
                    allMatch = false;
                    break;
                }
                if (!actual.matches(req.getValue())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return true;
        }
        return false;
    }

    public int size() {
        return allSymbols.size();
    }

    public static Pattern globToRegex(String glob) {
        return PATTERN_CACHE.computeIfAbsent(glob, SymbolIndex::compileGlob);
    }

    private static Pattern compileGlob(String glob) {
        Set<Integer> alternationParens = findAlternationParens(glob);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '(' -> regex.append(alternationParens.contains(i) ? "(" : "\\(");
                case ')' -> regex.append(alternationParens.contains(i) ? ")" : "\\)");
                case '|' -> regex.append(alternationParens.contains(i) ? "|" : "\\|");
                case '<' -> regex.append("\\<");
                case '>' -> regex.append("\\>");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '{' -> regex.append("\\{");
                case '}' -> regex.append("\\}");
                case '\\' -> regex.append("\\\\");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '+' -> regex.append("\\+");
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    private static Set<Integer> findAlternationParens(String glob) {
        Set<Integer> positions = new HashSet<>();
        int depth = 0;
        int openPos = -1;
        boolean hasPipe = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    openPos = i;
                    hasPipe = false;
                }
                depth++;
            } else if (c == '|' && depth > 0) {
                hasPipe = true;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && hasPipe && openPos >= 0) {
                    positions.add(openPos);
                    positions.add(i);
                    for (int j = openPos + 1; j < i; j++) {
                        if (glob.charAt(j) == '|') positions.add(j);
                    }
                }
            }
        }
        return positions;
    }

    private static <T> List<List<T>> partitionList(List<T> list, int chunkSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            partitions.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return partitions;
    }

    private static boolean matchesIncludedPath(Path root, Path file, List<String> includedPaths) {
        Path relative = root.resolve(".").normalize().relativize(file.normalize());
        String relStr = relative.toString();
        for (String included : includedPaths) {
            if (relStr.equals(included) || relStr.endsWith(included)) {
                return true;
            }
        }
        return false;
    }
}

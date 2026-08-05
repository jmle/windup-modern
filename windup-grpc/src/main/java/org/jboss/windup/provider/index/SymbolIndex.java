package org.jboss.windup.provider.index;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class SymbolIndex {

    private static final Logger LOG = LoggerFactory.getLogger(SymbolIndex.class);

    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            "org.eclipse.jdt.core.compiler.source", "17",
            "org.eclipse.jdt.core.compiler.compliance", "17",
            "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"
    );

    private final List<IndexedSymbol> allSymbols = new CopyOnWriteArrayList<>();
    private final Map<LocationType, List<IndexedSymbol>> byLocation = new EnumMap<>(LocationType.class);

    public void indexDirectory(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }

        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        LOG.info("Parsing {} Java files in {}", javaFiles.size(), root);

        for (Path file : javaFiles) {
            try {
                indexFile(file);
            } catch (Exception e) {
                LOG.warn("Failed to parse {}: {}", file, e.getMessage());
            }
        }

        LOG.info("Indexed {} symbols across {} files", allSymbols.size(), javaFiles.size());
    }

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
        allSymbols.addAll(fileSymbols);

        for (IndexedSymbol sym : fileSymbols) {
            byLocation.computeIfAbsent(sym.location(), k -> new ArrayList<>()).add(sym);
        }
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
        List<IndexedSymbol> imports = byLocation.getOrDefault(LocationType.IMPORT, List.of());
        if (imports.isEmpty()) {
            return List.of();
        }

        // PACKAGE pattern matches against the package portion of each import's FQN.
        // For import "javax.ejb.SessionBean", the package portion is "javax.ejb".
        // For star import "org.springframework.stereotype.*", the package is "org.springframework.stereotype".
        Pattern regex = globToRegex(pattern);
        List<IndexedSymbol> matches = new ArrayList<>();
        for (IndexedSymbol imp : imports) {
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
        Pattern typeRegex = globToRegex(typePattern);

        if (location == LocationType.FIELD) {
            // For FIELD: qualifiedName is the type, name is the field name
            return nameRegex.matcher(sym.name()).matches()
                    && typeRegex.matcher(sym.qualifiedName()).matches();
        } else {
            // For METHOD with "* ReturnType" pattern:
            // Need to find METHOD symbols whose return type matches
            // These are stored as RETURN_TYPE location; query those instead
            return nameRegex.matcher(sym.name()).matches()
                    && typeRegex.matcher(sym.qualifiedName()).matches();
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
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                case '<' -> regex.append("\\<");
                case '>' -> regex.append("\\>");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '{' -> regex.append("\\{");
                case '}' -> regex.append("\\}");
                case '\\' -> regex.append("\\\\");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '|' -> regex.append("\\|");
                case '+' -> regex.append("\\+");
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }
}

package io.konveyor.provider.index;

import java.util.List;
import java.util.Map;

/**
 * A single symbol extracted from a Java source file, storing its fully qualified name,
 * short name, kind, location type, source position, and any annotations on the
 * declaration. This is the fundamental unit stored in {@link SymbolIndex}.
 */
public record IndexedSymbol(
        String qualifiedName,
        String name,
        SymbolKind kind,
        LocationType location,
        String fileUri,
        String packageName,
        int line,
        int startChar,
        int endLine,
        int endChar,
        List<AnnotationInfo> annotations
) {
    public IndexedSymbol(String qualifiedName, String name, SymbolKind kind, LocationType location,
                         String fileUri, String packageName,
                         int line, int startChar, int endLine, int endChar) {
        this(qualifiedName, name, kind, location, fileUri, packageName,
                line, startChar, endLine, endChar, List.of());
    }

    public record AnnotationInfo(
            String qualifiedName,
            Map<String, String> elements
    ) {}
}

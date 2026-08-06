package io.konveyor.provider.index;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

/**
 * Eclipse JDT {@link ASTVisitor} that walks a parsed {@link CompilationUnit} and collects
 * {@link IndexedSymbol} entries for all relevant AST nodes: imports, type declarations,
 * method declarations, field declarations, annotations, constructor calls, method
 * invocations, variable declarations, and instanceof checks.
 */
public class SymbolCollector extends ASTVisitor {

    private final CompilationUnit compilationUnit;
    private final String fileUri;
    private final List<IndexedSymbol> symbols = new ArrayList<>();
    private final Map<String, String> importMap = new HashMap<>();
    private final List<String> starImportPackages = new ArrayList<>();
    private String packageName = "";

    public SymbolCollector(CompilationUnit compilationUnit, String fileUri) {
        this.compilationUnit = compilationUnit;
        this.fileUri = fileUri;
        extractPackage();
        buildImportMap();
    }

    private void extractPackage() {
        if (compilationUnit.getPackage() != null) {
            packageName = compilationUnit.getPackage().getName().getFullyQualifiedName();
        }
    }

    @SuppressWarnings("unchecked")
    private void buildImportMap() {
        for (ImportDeclaration imp : (List<ImportDeclaration>) compilationUnit.imports()) {
            if (imp.isOnDemand()) {
                starImportPackages.add(imp.getName().getFullyQualifiedName());
                continue;
            }
            String fqn = imp.getName().getFullyQualifiedName();
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            importMap.put(simpleName, fqn);
        }
    }

    public List<IndexedSymbol> getSymbols() {
        return Collections.unmodifiableList(symbols);
    }

    public String getPackageName() {
        return packageName;
    }

    private List<String> resolveTypeName(String name) {
        if (name.contains(".")) {
            return List.of(name);
        }
        String explicit = importMap.get(name);
        if (explicit != null) {
            return List.of(explicit);
        }
        if (!starImportPackages.isEmpty()) {
            return starImportPackages.stream()
                    .map(pkg -> pkg + "." + name)
                    .toList();
        }
        if (!packageName.isEmpty()) {
            return List.of(packageName + "." + name);
        }
        return List.of(name);
    }

    private String resolveToSingleName(String name) {
        List<String> candidates = resolveTypeName(name);
        return candidates.get(0);
    }

    // ------------------------------------------------------------------
    // Import declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(ImportDeclaration node) {
        String fqn = node.getName().getFullyQualifiedName();
        if (node.isOnDemand()) {
            addSymbol(fqn + ".*", fqn + ".*", SymbolKind.MODULE, LocationType.IMPORT, node);
        } else {
            addSymbol(fqn, fqn, SymbolKind.MODULE, LocationType.IMPORT, node);
        }
        // PACKAGE symbols are derived from IMPORT symbols at query time in SymbolIndex
        return false;
    }

    // ------------------------------------------------------------------
    // Type declarations (class / interface)
    // ------------------------------------------------------------------

    @Override
    public boolean visit(TypeDeclaration node) {
        String simpleName = node.getName().getIdentifier();
        String fqn = buildQualifiedName(node);

        List<IndexedSymbol.AnnotationInfo> annots = collectAnnotations(node);

        // CLASS location — the class declaration itself
        addSymbol(fqn, simpleName, SymbolKind.CLASS, LocationType.CLASS, node.getName(), annots);

        // TYPE location — the class as a type reference
        addSymbol(fqn, simpleName, SymbolKind.CLASS, LocationType.TYPE, node.getName(), annots);

        // Superclass (INHERITANCE) — name is the CLASS that extends, not the superclass
        if (node.getSuperclassType() != null) {
            Type superType = node.getSuperclassType();
            for (String resolved : resolveTypeName(typeToString(superType))) {
                addSymbol(resolved, simpleName, SymbolKind.CLASS, LocationType.INHERITANCE, superType);
            }
        }

        // Interfaces (IMPLEMENTS_TYPE) — name is the CLASS that implements, not the interface
        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        LocationType ifaceLocType = node.isInterface() ? LocationType.INHERITANCE : LocationType.IMPLEMENTS_TYPE;
        for (Type iface : superInterfaces) {
            for (String resolved : resolveTypeName(typeToString(iface))) {
                addSymbol(resolved, simpleName, SymbolKind.CLASS, ifaceLocType, iface);
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Enum declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(EnumDeclaration node) {
        String simpleName = node.getName().getIdentifier();
        String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        List<IndexedSymbol.AnnotationInfo> annots = collectAnnotations(node);
        addSymbol(fqn, simpleName, SymbolKind.ENUM, LocationType.CLASS, node.getName(), annots);

        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        for (Type iface : superInterfaces) {
            for (String resolved : resolveTypeName(typeToString(iface))) {
                addSymbol(resolved, typeToString(iface), SymbolKind.CLASS, LocationType.IMPLEMENTS_TYPE, iface);
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Method declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MethodDeclaration node) {
        String methodName = node.getName().getIdentifier();
        String containingClass = getContainingClassName(node);
        String methodFqn = containingClass.isEmpty() ? methodName : containingClass + "." + methodName;

        List<IndexedSymbol.AnnotationInfo> annots = collectMethodAnnotations(node);

        // Build signature variant: pkg.Class.<T>method(ParamType)
        String signatureFqn = buildMethodSignature(node, containingClass);

        // METHOD location — method declarations
        addSymbol(methodFqn, methodName, SymbolKind.METHOD, LocationType.METHOD, node.getName(), annots);
        if (!signatureFqn.equals(methodFqn)) {
            addSymbol(signatureFqn, methodName, SymbolKind.METHOD, LocationType.METHOD, node.getName(), annots);
        }

        // Return type (RETURN_TYPE)
        if (!node.isConstructor() && node.getReturnType2() != null) {
            String returnTypeStr = typeToString(node.getReturnType2());
            for (String resolved : resolveTypeName(returnTypeStr)) {
                // For METHOD with "* ReturnType" pattern: the method's FQN is matchable
                // as "methodName returnType" so store both the return type as qualifier
                // and the method name as the symbol name
                addSymbol(resolved, methodName, SymbolKind.METHOD, LocationType.RETURN_TYPE, node.getReturnType2());
            }
        }

        // Parameters — TYPE references inside method, kind=Method, name=containing method
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = node.parameters();
        for (SingleVariableDeclaration param : params) {
            for (String resolved : resolveTypeName(typeToString(param.getType()))) {
                addSymbol(resolved, methodName, SymbolKind.METHOD, LocationType.TYPE, param.getType());
            }
        }

        // Thrown exceptions — TYPE references inside method
        @SuppressWarnings("unchecked")
        List<Type> thrown = node.thrownExceptionTypes();
        for (Type t : thrown) {
            for (String resolved : resolveTypeName(typeToString(t))) {
                addSymbol(resolved, methodName, SymbolKind.METHOD, LocationType.TYPE, t);
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Field declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(FieldDeclaration node) {
        String typeStr = typeToString(node.getType());
        List<String> resolvedTypes = resolveTypeName(typeStr);

        List<IndexedSymbol.AnnotationInfo> annots = collectFieldAnnotations(node);

        @SuppressWarnings("unchecked")
        List<VariableDeclarationFragment> fragments = node.fragments();
        for (VariableDeclarationFragment frag : fragments) {
            String fieldName = frag.getName().getIdentifier();
            for (String resolved : resolvedTypes) {
                addSymbol(resolved, fieldName, SymbolKind.FIELD, LocationType.FIELD, frag.getName(), annots);
                // Also emit a TYPE reference for the field type usage (class-level, so Field kind)
                addSymbol(resolved, fieldName, SymbolKind.FIELD, LocationType.TYPE, node.getType());
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Local variable declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(VariableDeclarationStatement node) {
        String typeStr = typeToString(node.getType());
        String containingMethod = getContainingMethodName(node);
        for (String resolved : resolveTypeName(typeStr)) {
            @SuppressWarnings("unchecked")
            List<VariableDeclarationFragment> fragments = node.fragments();
            for (VariableDeclarationFragment frag : fragments) {
                addSymbol(resolved, frag.getName().getIdentifier(), SymbolKind.VARIABLE,
                        LocationType.VARIABLE_DECLARATION, frag.getName());
                // Also emit a TYPE reference for this usage
                if (!containingMethod.isEmpty()) {
                    addSymbol(resolved, containingMethod, SymbolKind.METHOD,
                            LocationType.TYPE, node.getType());
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Constructor calls (new Foo())
    // ------------------------------------------------------------------

    @Override
    public boolean visit(ClassInstanceCreation node) {
        String typeStr = typeToString(node.getType());
        String containingMethod = getContainingMethodName(node);
        for (String resolved : resolveTypeName(typeStr)) {
            addSymbol(resolved, typeStr, SymbolKind.CLASS, LocationType.CONSTRUCTOR_CALL, node.getType());
            // Also emit a TYPE reference for this usage
            if (!containingMethod.isEmpty()) {
                addSymbol(resolved, containingMethod, SymbolKind.METHOD,
                        LocationType.TYPE, node.getType());
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Method invocations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MethodInvocation node) {
        String calledMethodName = node.getName().getIdentifier();
        String containingMethodName = getContainingMethodName(node);

        if (node.getExpression() != null) {
            String exprStr = node.getExpression().toString();
            for (String resolved : resolveTypeName(exprStr)) {
                String callFqn = resolved + "." + calledMethodName;
                addSymbol(callFqn, containingMethodName, SymbolKind.METHOD, LocationType.METHOD_CALL, node.getName());
            }
        } else {
            String containingClass = getContainingClassNameFromNode(node);
            String fqn = containingClass.isEmpty() ? calledMethodName : containingClass + "." + calledMethodName;
            addSymbol(fqn, containingMethodName, SymbolKind.METHOD, LocationType.METHOD_CALL, node.getName());
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Annotations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MarkerAnnotation node) {
        addAnnotationSymbol(node);
        return true;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        addAnnotationSymbol(node);
        return true;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        addAnnotationSymbol(node);
        return true;
    }

    private void addAnnotationSymbol(Annotation annotation) {
        String simpleName = annotation.getTypeName().getFullyQualifiedName();
        for (String resolved : resolveTypeName(simpleName)) {
            addSymbol(resolved, simpleName, SymbolKind.PROPERTY, LocationType.ANNOTATION, annotation);
        }
    }

    // ------------------------------------------------------------------
    // instanceof expressions
    // ------------------------------------------------------------------

    @Override
    public boolean visit(InstanceofExpression node) {
        String typeStr = typeToString(node.getRightOperand());
        String containingMethod = getContainingMethodName(node);
        SymbolKind kind = containingMethod.isEmpty() ? SymbolKind.CLASS : SymbolKind.METHOD;
        String name = containingMethod.isEmpty() ? typeStr : containingMethod;
        for (String resolved : resolveTypeName(typeStr)) {
            addSymbol(resolved, name, kind, LocationType.TYPE, node.getRightOperand());
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void addSymbol(String qualifiedName, String name, SymbolKind kind,
                           LocationType location, ASTNode node) {
        addSymbol(qualifiedName, name, kind, location, node, List.of());
    }

    private void addSymbol(String qualifiedName, String name, SymbolKind kind,
                           LocationType location, ASTNode node,
                           List<IndexedSymbol.AnnotationInfo> annotations) {
        int startLine = compilationUnit.getLineNumber(node.getStartPosition()) - 1;
        int startCol = compilationUnit.getColumnNumber(node.getStartPosition());
        int endOffset = node.getStartPosition() + node.getLength();
        int endLine = compilationUnit.getLineNumber(endOffset) - 1;
        int endCol = compilationUnit.getColumnNumber(endOffset);

        symbols.add(new IndexedSymbol(
                qualifiedName, name, kind, location, fileUri, packageName,
                startLine, startCol, endLine, endCol, annotations
        ));
    }

    private String buildQualifiedName(TypeDeclaration node) {
        String simpleName = node.getName().getIdentifier();
        if (node.getParent() instanceof TypeDeclaration parent) {
            return buildQualifiedName(parent) + "." + simpleName;
        }
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private String getContainingClassName(MethodDeclaration node) {
        ASTNode parent = node.getParent();
        if (parent instanceof TypeDeclaration td) {
            return buildQualifiedName(td);
        } else if (parent instanceof EnumDeclaration ed) {
            String simpleName = ed.getName().getIdentifier();
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }
        return "";
    }

    private String getContainingMethodName(ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null) {
            if (current instanceof MethodDeclaration md) {
                return md.getName().getIdentifier();
            }
            current = current.getParent();
        }
        return "";
    }

    private String getContainingClassNameFromNode(ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null) {
            if (current instanceof TypeDeclaration td) {
                return buildQualifiedName(td);
            } else if (current instanceof EnumDeclaration ed) {
                String simpleName = ed.getName().getIdentifier();
                return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            }
            current = current.getParent();
        }
        return "";
    }

    private String buildMethodSignature(MethodDeclaration node, String containingClass) {
        String methodName = node.getName().getIdentifier();

        @SuppressWarnings("unchecked")
        List<TypeParameter> typeParams = node.typeParameters();
        StringBuilder sig = new StringBuilder();
        if (!containingClass.isEmpty()) {
            sig.append(containingClass).append(".");
        }
        if (!typeParams.isEmpty()) {
            sig.append("<");
            for (int i = 0; i < typeParams.size(); i++) {
                if (i > 0) sig.append(",");
                sig.append(typeParams.get(i).getName().getIdentifier());
            }
            sig.append(">");
        }
        sig.append(methodName);

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = node.parameters();
        if (!params.isEmpty() || !typeParams.isEmpty()) {
            sig.append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sig.append(",");
                sig.append(typeToString(params.get(i).getType()));
            }
            sig.append(")");
        }

        return sig.toString();
    }

    @SuppressWarnings("unchecked")
    private List<IndexedSymbol.AnnotationInfo> collectAnnotations(AbstractTypeDeclaration node) {
        List<IndexedSymbol.AnnotationInfo> result = new ArrayList<>();
        for (Object mod : (List<Object>) node.modifiers()) {
            if (mod instanceof Annotation a) {
                result.add(buildAnnotationInfo(a));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<IndexedSymbol.AnnotationInfo> collectMethodAnnotations(MethodDeclaration node) {
        List<IndexedSymbol.AnnotationInfo> result = new ArrayList<>();
        for (Object mod : (List<Object>) node.modifiers()) {
            if (mod instanceof Annotation a) {
                result.add(buildAnnotationInfo(a));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<IndexedSymbol.AnnotationInfo> collectFieldAnnotations(FieldDeclaration node) {
        List<IndexedSymbol.AnnotationInfo> result = new ArrayList<>();
        for (Object mod : (List<Object>) node.modifiers()) {
            if (mod instanceof Annotation a) {
                result.add(buildAnnotationInfo(a));
            }
        }
        return result;
    }

    private IndexedSymbol.AnnotationInfo buildAnnotationInfo(Annotation annotation) {
        String typeName = annotation.getTypeName().getFullyQualifiedName();
        String resolved = resolveToSingleName(typeName);
        Map<String, String> elements = new LinkedHashMap<>();

        if (annotation instanceof NormalAnnotation normal) {
            @SuppressWarnings("unchecked")
            List<MemberValuePair> pairs = normal.values();
            for (MemberValuePair pair : pairs) {
                elements.put(pair.getName().getIdentifier(),
                        pair.getValue() != null ? pair.getValue().toString() : "");
            }
        } else if (annotation instanceof SingleMemberAnnotation single) {
            if (single.getValue() != null) {
                elements.put("value", single.getValue().toString());
            }
        }

        return new IndexedSymbol.AnnotationInfo(resolved, elements);
    }

    static String typeToString(Type type) {
        if (type == null) return "void";
        return type.toString();
    }
}

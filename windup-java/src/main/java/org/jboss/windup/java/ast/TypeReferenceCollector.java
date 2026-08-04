package org.jboss.windup.java.ast;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.java.model.JavaClassReference.ReferenceType;
import org.jboss.windup.model.FileModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link ASTVisitor} that collects all type references found in a Java
 * source file, creating {@link JavaClassReference} instances for each one.
 *
 * <p>This visitor tracks where types are referenced: imports, type usages,
 * method calls, field declarations, annotations, inheritance, constructor
 * calls, instanceof expressions, throws clauses, catch clauses, and local
 * variable declarations.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   TypeReferenceCollector collector = new TypeReferenceCollector(compilationUnit, sourceFile);
 *   compilationUnit.accept(collector);
 *   List&lt;JavaClassReference&gt; refs = collector.getReferences();
 * </pre>
 */
public class TypeReferenceCollector extends ASTVisitor {

    private final CompilationUnit compilationUnit;
    private final FileModel sourceFile;
    private final List<JavaClassReference> references = new ArrayList<>();
    private final Map<String, String> importMap = new HashMap<>();

    public TypeReferenceCollector(CompilationUnit compilationUnit, FileModel sourceFile) {
        this.compilationUnit = compilationUnit;
        this.sourceFile = sourceFile;
        buildImportMap();
    }

    @SuppressWarnings("unchecked")
    private void buildImportMap() {
        for (ImportDeclaration imp : (List<ImportDeclaration>) compilationUnit.imports()) {
            if (imp.isOnDemand()) {
                continue;
            }
            String fqn = imp.getName().getFullyQualifiedName();
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            importMap.put(simpleName, fqn);
        }
    }

    private String resolveTypeName(String name) {
        if (name.contains(".")) {
            return name;
        }
        return importMap.getOrDefault(name, name);
    }

    public List<JavaClassReference> getReferences() {
        return Collections.unmodifiableList(references);
    }

    // ------------------------------------------------------------------
    // Import declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(ImportDeclaration node) {
        String name = node.getName().getFullyQualifiedName();
        if (node.isOnDemand()) {
            name += ".*";
        }
        addReference(name, ReferenceType.IMPORT, node.getStartPosition());
        return false;
    }

    // ------------------------------------------------------------------
    // Type declarations (class / interface)
    // ------------------------------------------------------------------

    @Override
    public boolean visit(TypeDeclaration node) {
        // Record the type declaration itself
        String typeName = node.getName().getIdentifier();
        addReference(typeName, ReferenceType.TYPE, node.getName().getStartPosition());

        // Superclass (inheritance)
        if (node.getSuperclassType() != null) {
            addTypeReference(node.getSuperclassType(), ReferenceType.INHERITANCE);
        }

        // Implemented/extended interfaces
        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        ReferenceType ifaceRefType = node.isInterface()
                ? ReferenceType.INHERITANCE
                : ReferenceType.IMPLEMENTS_TYPE;
        for (Type iface : superInterfaces) {
            addTypeReference(iface, ifaceRefType);
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Enum declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(EnumDeclaration node) {
        String typeName = node.getName().getIdentifier();
        addReference(typeName, ReferenceType.TYPE, node.getName().getStartPosition());

        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        for (Type iface : superInterfaces) {
            addTypeReference(iface, ReferenceType.IMPLEMENTS_TYPE);
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Method declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MethodDeclaration node) {
        // Return type
        if (!node.isConstructor() && node.getReturnType2() != null) {
            addTypeReference(node.getReturnType2(), ReferenceType.RETURN_TYPE);
        }

        // Parameters
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = node.parameters();
        for (SingleVariableDeclaration param : params) {
            addTypeReference(param.getType(), ReferenceType.METHOD_PARAMETER);
        }

        // Thrown exceptions
        @SuppressWarnings("unchecked")
        List<Type> thrownExceptions = node.thrownExceptionTypes();
        for (Type thrown : thrownExceptions) {
            addTypeReference(thrown, ReferenceType.THROWS_METHOD_DECLARATION);
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Field declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(FieldDeclaration node) {
        addTypeReference(node.getType(), ReferenceType.FIELD_DECLARATION);
        return true;
    }

    // ------------------------------------------------------------------
    // Local variable declarations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(VariableDeclarationStatement node) {
        addTypeReference(node.getType(), ReferenceType.VARIABLE_DECLARATION);
        return true;
    }

    // ------------------------------------------------------------------
    // Constructor calls (new Foo())
    // ------------------------------------------------------------------

    @Override
    public boolean visit(ClassInstanceCreation node) {
        addTypeReference(node.getType(), ReferenceType.CONSTRUCTOR_CALL);
        return true;
    }

    // ------------------------------------------------------------------
    // Method invocations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MethodInvocation node) {
        // Without binding resolution we record the method name and
        // expression type if available.
        if (node.getExpression() != null) {
            String expr = node.getExpression().toString();
            String methodCall = expr + "." + node.getName().getIdentifier();
            addReference(methodCall, ReferenceType.METHOD_CALL, node.getStartPosition());
        } else {
            addReference(node.getName().getIdentifier(), ReferenceType.METHOD_CALL,
                    node.getName().getStartPosition());
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Annotations
    // ------------------------------------------------------------------

    @Override
    public boolean visit(MarkerAnnotation node) {
        addAnnotationReference(node);
        return true;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        addAnnotationReference(node);
        return true;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        addAnnotationReference(node);
        return true;
    }

    // ------------------------------------------------------------------
    // instanceof expressions
    // ------------------------------------------------------------------

    @Override
    public boolean visit(InstanceofExpression node) {
        addTypeReference(node.getRightOperand(), ReferenceType.INSTANCE_OF);
        return true;
    }

    // ------------------------------------------------------------------
    // throw statements
    // ------------------------------------------------------------------

    @Override
    public boolean visit(ThrowStatement node) {
        if (node.getExpression() instanceof ClassInstanceCreation creation) {
            addTypeReference(creation.getType(), ReferenceType.THROW_STATEMENT);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // catch clauses
    // ------------------------------------------------------------------

    @Override
    public boolean visit(CatchClause node) {
        addTypeReference(node.getException().getType(), ReferenceType.CATCH_EXCEPTION_STATEMENT);
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void addAnnotationReference(Annotation annotation) {
        String name = resolveTypeName(annotation.getTypeName().getFullyQualifiedName());
        addReference(name, ReferenceType.ANNOTATION, annotation.getStartPosition());
    }

    private void addTypeReference(Type type, ReferenceType refType) {
        if (type == null) {
            return;
        }
        String name = resolveTypeName(JavaASTParser.typeToString(type));
        addReference(name, refType, type.getStartPosition());
    }

    private void addReference(String qualifiedName, ReferenceType refType, int startPosition) {
        int line = compilationUnit.getLineNumber(startPosition);
        int column = compilationUnit.getColumnNumber(startPosition);

        JavaClassReference ref = new JavaClassReference(qualifiedName, refType, line, column);
        ref.setSourceFile(sourceFile);
        references.add(ref);
    }
}

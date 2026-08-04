package org.jboss.windup.java.ast;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import org.jboss.windup.java.model.JavaAnnotationModel;
import org.jboss.windup.java.model.JavaClassModel;
import org.jboss.windup.java.model.JavaMethodModel;
import org.jboss.windup.java.model.JavaSourceFileModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses Java source files using Eclipse JDT and populates the Windup Java
 * model objects.
 *
 * <p>This service creates an {@link ASTParser} configured for Java 17 source
 * level, parses the supplied source code into a {@link CompilationUnit}, then
 * walks the AST to extract package declarations, import statements, type
 * declarations (classes, interfaces, enums), method declarations, and
 * annotation usages.</p>
 */
@ApplicationScoped
public class JavaASTParser {

    private static final Logger LOG = Logger.getLogger(JavaASTParser.class.getName());

    /**
     * Compiler options that configure the JDT parser for Java 17 source level.
     * Without these, the standalone JDT parser defaults to JLS2 parsing
     * rules and fails to recognize enums, static imports, and other
     * post-Java-1.4 syntax.
     */
    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            "org.eclipse.jdt.core.compiler.source", "17",
            "org.eclipse.jdt.core.compiler.compliance", "17",
            "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"
    );

    /**
     * Parses the given Java source code and returns a populated
     * {@link JavaSourceFileModel}.
     *
     * @param sourceFile the path to the source file (used for the model, not read here)
     * @param sourceCode the Java source code to parse
     * @return a fully populated {@link JavaSourceFileModel}
     */
    public JavaSourceFileModel parse(Path sourceFile, String sourceCode) {
        JavaSourceFileModel sourceFileModel = new JavaSourceFileModel(sourceFile);

        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(sourceCode.toCharArray());
        parser.setCompilerOptions(COMPILER_OPTIONS);
        parser.setResolveBindings(false);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        extractPackage(cu, sourceFileModel);
        extractImports(cu, sourceFileModel);
        extractTypes(cu, sourceFileModel);

        return sourceFileModel;
    }

    // ------------------------------------------------------------------
    // Package
    // ------------------------------------------------------------------

    private void extractPackage(CompilationUnit cu, JavaSourceFileModel model) {
        if (cu.getPackage() != null) {
            model.setPackageName(cu.getPackage().getName().getFullyQualifiedName());
        }
    }

    // ------------------------------------------------------------------
    // Imports
    // ------------------------------------------------------------------

    private void extractImports(CompilationUnit cu, JavaSourceFileModel model) {
        @SuppressWarnings("unchecked")
        List<ImportDeclaration> imports = cu.imports();
        for (ImportDeclaration imp : imports) {
            String name = imp.getName().getFullyQualifiedName();
            if (imp.isOnDemand()) {
                name += ".*";
            }
            if (imp.isStatic()) {
                name = "static " + name;
            }
            model.getImports().add(name);
        }
    }

    // ------------------------------------------------------------------
    // Type declarations
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void extractTypes(CompilationUnit cu, JavaSourceFileModel model) {
        String packageName = model.getPackageName() != null ? model.getPackageName() : "";

        List<AbstractTypeDeclaration> types = cu.types();
        for (AbstractTypeDeclaration type : types) {
            if (type instanceof TypeDeclaration td) {
                JavaClassModel classModel = buildClassModel(td, packageName, model);
                model.getJavaClasses().add(classModel);
            } else if (type instanceof EnumDeclaration ed) {
                JavaClassModel classModel = buildEnumModel(ed, packageName, model);
                model.getJavaClasses().add(classModel);
            } else if (type instanceof AnnotationTypeDeclaration atd) {
                // Annotation type declarations are treated as interfaces
                JavaClassModel classModel = buildAnnotationTypeModel(atd, packageName, model);
                model.getJavaClasses().add(classModel);
            }
        }
    }

    // ------------------------------------------------------------------
    // Class / Interface building
    // ------------------------------------------------------------------

    private JavaClassModel buildClassModel(TypeDeclaration node, String packageName,
                                           JavaSourceFileModel sourceFileModel) {
        String simpleName = node.getName().getIdentifier();
        String qualifiedName = buildQualifiedName(node, packageName);

        JavaClassModel classModel = new JavaClassModel(qualifiedName);
        classModel.setPackageName(packageName);
        classModel.setClassName(simpleName);
        classModel.setSourceFileModel(sourceFileModel);

        // Flags
        int modifiers = node.getModifiers();
        classModel.setAbstractClass(Modifier.isAbstract(modifiers));
        classModel.setPublicClass(Modifier.isPublic(modifiers));
        classModel.setInterfaceType(node.isInterface());
        classModel.setEnumType(false);

        // Superclass
        if (node.getSuperclassType() != null) {
            classModel.setSuperClassName(typeToString(node.getSuperclassType()));
        }

        // Interfaces
        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        for (Type iface : superInterfaces) {
            classModel.getInterfaces().add(typeToString(iface));
        }

        // Methods
        for (MethodDeclaration method : node.getMethods()) {
            classModel.getMethods().add(buildMethodModel(method));
        }

        // Annotations on the type
        extractAnnotations(node, classModel);

        // Nested types
        for (TypeDeclaration nested : node.getTypes()) {
            JavaClassModel nestedModel = buildClassModel(nested, packageName, sourceFileModel);
            sourceFileModel.getJavaClasses().add(nestedModel);
        }

        return classModel;
    }

    private JavaClassModel buildEnumModel(EnumDeclaration node, String packageName,
                                          JavaSourceFileModel sourceFileModel) {
        String simpleName = node.getName().getIdentifier();
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        JavaClassModel classModel = new JavaClassModel(qualifiedName);
        classModel.setPackageName(packageName);
        classModel.setClassName(simpleName);
        classModel.setSourceFileModel(sourceFileModel);
        classModel.setEnumType(true);

        int modifiers = node.getModifiers();
        classModel.setPublicClass(Modifier.isPublic(modifiers));

        // Interfaces implemented by enum
        @SuppressWarnings("unchecked")
        List<Type> superInterfaces = node.superInterfaceTypes();
        for (Type iface : superInterfaces) {
            classModel.getInterfaces().add(typeToString(iface));
        }

        // Annotations on the enum
        extractAnnotations(node, classModel);

        return classModel;
    }

    private JavaClassModel buildAnnotationTypeModel(AnnotationTypeDeclaration node,
                                                     String packageName,
                                                     JavaSourceFileModel sourceFileModel) {
        String simpleName = node.getName().getIdentifier();
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        JavaClassModel classModel = new JavaClassModel(qualifiedName);
        classModel.setPackageName(packageName);
        classModel.setClassName(simpleName);
        classModel.setSourceFileModel(sourceFileModel);
        classModel.setInterfaceType(true);

        int modifiers = node.getModifiers();
        classModel.setPublicClass(Modifier.isPublic(modifiers));

        extractAnnotations(node, classModel);

        return classModel;
    }

    // ------------------------------------------------------------------
    // Method building
    // ------------------------------------------------------------------

    private JavaMethodModel buildMethodModel(MethodDeclaration node) {
        String methodName = node.getName().getIdentifier();

        String returnType;
        if (node.isConstructor()) {
            returnType = "void";
        } else if (node.getReturnType2() != null) {
            returnType = typeToString(node.getReturnType2());
        } else {
            returnType = "void";
        }

        JavaMethodModel methodModel = new JavaMethodModel(methodName, returnType);

        // Parameters
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = node.parameters();
        for (SingleVariableDeclaration param : params) {
            methodModel.getParameterTypes().add(typeToString(param.getType()));
        }

        // Annotations on the method
        @SuppressWarnings("unchecked")
        List<Object> modifiers = node.modifiers();
        for (Object mod : modifiers) {
            if (mod instanceof Annotation annotation) {
                methodModel.getAnnotations().add(buildAnnotationModel(annotation));
            }
        }

        return methodModel;
    }

    // ------------------------------------------------------------------
    // Annotation building
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void extractAnnotations(AbstractTypeDeclaration node, JavaClassModel classModel) {
        List<Object> modifiers = node.modifiers();
        for (Object mod : modifiers) {
            if (mod instanceof Annotation annotation) {
                classModel.getAnnotations().add(buildAnnotationModel(annotation));
            }
        }
    }

    private JavaAnnotationModel buildAnnotationModel(Annotation annotation) {
        String annotationType = annotation.getTypeName().getFullyQualifiedName();
        JavaAnnotationModel model = new JavaAnnotationModel(annotationType);

        if (annotation instanceof NormalAnnotation normal) {
            List<MemberValuePair> pairs = normal.values();
            for (MemberValuePair pair : pairs) {
                model.setValue(pair.getName().getIdentifier(),
                        pair.getValue() != null ? pair.getValue().toString() : null);
            }
        } else if (annotation instanceof SingleMemberAnnotation single) {
            if (single.getValue() != null) {
                model.setValue("value", single.getValue().toString());
            }
        }
        // MarkerAnnotation has no values

        return model;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds a qualified name for a type declaration, handling nesting.
     */
    private String buildQualifiedName(TypeDeclaration node, String packageName) {
        String simpleName = node.getName().getIdentifier();

        // Check if this is a nested type
        if (node.getParent() instanceof TypeDeclaration parent) {
            String parentQualified = buildQualifiedName(parent, packageName);
            return parentQualified + "." + simpleName;
        }

        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * Converts a JDT {@link Type} node to its string representation.
     */
    static String typeToString(Type type) {
        if (type == null) {
            return "void";
        }
        return type.toString();
    }
}

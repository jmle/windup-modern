package org.jboss.windup.java.ast;

import org.jboss.windup.java.model.JavaAnnotationModel;
import org.jboss.windup.java.model.JavaClassModel;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.java.model.JavaClassReference.ReferenceType;
import org.jboss.windup.java.model.JavaMethodModel;
import org.jboss.windup.java.model.JavaSourceFileModel;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JavaASTParser} and {@link TypeReferenceCollector}.
 */
class JavaASTParserTest {

    private JavaASTParser parser;

    @BeforeEach
    void setUp() {
        parser = new JavaASTParser();
    }

    // ------------------------------------------------------------------
    // Simple class
    // ------------------------------------------------------------------

    @Test
    void parseSimpleClass() {
        String source = """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public class MyService {

                    public String greet(String name) {
                        return "Hello, " + name;
                    }

                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("MyService.java"), source);

        assertThat(result.getPackageName()).isEqualTo("com.example");
        assertThat(result.getImports()).containsExactly("java.util.List", "java.util.Map");

        assertThat(result.getJavaClasses()).hasSize(1);
        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getQualifiedName()).isEqualTo("com.example.MyService");
        assertThat(classModel.getClassName()).isEqualTo("MyService");
        assertThat(classModel.getPackageName()).isEqualTo("com.example");
        assertThat(classModel.isPublicClass()).isTrue();
        assertThat(classModel.isAbstractClass()).isFalse();
        assertThat(classModel.isInterfaceType()).isFalse();
        assertThat(classModel.isEnumType()).isFalse();

        assertThat(classModel.getMethods()).hasSize(2);
        JavaMethodModel greet = classModel.getMethods().get(0);
        assertThat(greet.getMethodName()).isEqualTo("greet");
        assertThat(greet.getReturnType()).isEqualTo("String");
        assertThat(greet.getParameterTypes()).containsExactly("String");

        JavaMethodModel add = classModel.getMethods().get(1);
        assertThat(add.getMethodName()).isEqualTo("add");
        assertThat(add.getReturnType()).isEqualTo("int");
        assertThat(add.getParameterTypes()).containsExactly("int", "int");
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------

    @Test
    void parseInterface() {
        String source = """
                package com.example.api;

                import java.util.Optional;

                public interface Repository<T> {

                    Optional<T> findById(String id);

                    void save(T entity);

                    void delete(String id);
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("Repository.java"), source);

        assertThat(result.getPackageName()).isEqualTo("com.example.api");
        assertThat(result.getImports()).containsExactly("java.util.Optional");

        assertThat(result.getJavaClasses()).hasSize(1);
        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getQualifiedName()).isEqualTo("com.example.api.Repository");
        assertThat(classModel.isInterfaceType()).isTrue();
        assertThat(classModel.isPublicClass()).isTrue();

        assertThat(classModel.getMethods()).hasSize(3);
        assertThat(classModel.getMethods().get(0).getMethodName()).isEqualTo("findById");
        assertThat(classModel.getMethods().get(1).getMethodName()).isEqualTo("save");
        assertThat(classModel.getMethods().get(2).getMethodName()).isEqualTo("delete");
    }

    // ------------------------------------------------------------------
    // Abstract class with inheritance
    // ------------------------------------------------------------------

    @Test
    void parseAbstractClass() {
        String source = """
                package com.example;

                import java.io.Serializable;

                public abstract class BaseEntity implements Serializable, Comparable<BaseEntity> {

                    public abstract String getId();

                    protected void validate() {
                    }
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("BaseEntity.java"), source);

        assertThat(result.getJavaClasses()).hasSize(1);
        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getQualifiedName()).isEqualTo("com.example.BaseEntity");
        assertThat(classModel.isAbstractClass()).isTrue();
        assertThat(classModel.isPublicClass()).isTrue();
        assertThat(classModel.getInterfaces()).containsExactly("Serializable", "Comparable<BaseEntity>");
        assertThat(classModel.getSuperClassName()).isNull();

        assertThat(classModel.getMethods()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Class with superclass
    // ------------------------------------------------------------------

    @Test
    void parseClassWithSuperclass() {
        String source = """
                package com.example;

                public class UserEntity extends BaseEntity {

                    public String getId() {
                        return "user-1";
                    }
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("UserEntity.java"), source);

        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getSuperClassName()).isEqualTo("BaseEntity");
        assertThat(classModel.getQualifiedName()).isEqualTo("com.example.UserEntity");
    }

    // ------------------------------------------------------------------
    // Annotations
    // ------------------------------------------------------------------

    @Test
    void parseClassWithAnnotations() {
        String source = """
                package com.example;

                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Named;

                @ApplicationScoped
                @Named("myService")
                public class AnnotatedService {

                    @Deprecated
                    public void oldMethod() {
                    }

                    @SuppressWarnings("unchecked")
                    public void newMethod() {
                    }
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("AnnotatedService.java"), source);

        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getAnnotations()).hasSize(2);

        JavaAnnotationModel appScoped = classModel.getAnnotations().get(0);
        assertThat(appScoped.getAnnotationType()).isEqualTo("ApplicationScoped");
        assertThat(appScoped.getValues()).isEmpty();

        JavaAnnotationModel named = classModel.getAnnotations().get(1);
        assertThat(named.getAnnotationType()).isEqualTo("Named");
        assertThat(named.getValues()).containsEntry("value", "\"myService\"");

        // Method annotations
        JavaMethodModel oldMethod = classModel.getMethods().get(0);
        assertThat(oldMethod.getAnnotations()).hasSize(1);
        assertThat(oldMethod.getAnnotations().get(0).getAnnotationType()).isEqualTo("Deprecated");

        JavaMethodModel newMethod = classModel.getMethods().get(1);
        assertThat(newMethod.getAnnotations()).hasSize(1);
        assertThat(newMethod.getAnnotations().get(0).getAnnotationType()).isEqualTo("SuppressWarnings");
    }

    // ------------------------------------------------------------------
    // Enum
    // ------------------------------------------------------------------

    @Test
    void parseEnum() {
        String source = """
                package com.example;

                public enum Status {
                    ACTIVE,
                    INACTIVE,
                    PENDING
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("Status.java"), source);

        assertThat(result.getJavaClasses()).hasSize(1);
        JavaClassModel classModel = result.getJavaClasses().get(0);
        assertThat(classModel.getQualifiedName()).isEqualTo("com.example.Status");
        assertThat(classModel.isEnumType()).isTrue();
        assertThat(classModel.isPublicClass()).isTrue();
    }

    // ------------------------------------------------------------------
    // Multiple classes in one file
    // ------------------------------------------------------------------

    @Test
    void parseMultipleClasses() {
        String source = """
                package com.example;

                public class Outer {
                    public void outerMethod() {}
                }

                class PackagePrivate {
                    void doSomething() {}
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("Outer.java"), source);

        // Two top-level types
        assertThat(result.getJavaClasses()).hasSize(2);
        assertThat(result.getJavaClasses().get(0).getQualifiedName()).isEqualTo("com.example.Outer");
        assertThat(result.getJavaClasses().get(0).isPublicClass()).isTrue();
        assertThat(result.getJavaClasses().get(1).getQualifiedName()).isEqualTo("com.example.PackagePrivate");
        assertThat(result.getJavaClasses().get(1).isPublicClass()).isFalse();
    }

    // ------------------------------------------------------------------
    // No package
    // ------------------------------------------------------------------

    @Test
    void parseClassWithoutPackage() {
        String source = """
                public class DefaultPackageClass {
                    public void doWork() {}
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("DefaultPackageClass.java"), source);

        assertThat(result.getPackageName()).isNull();
        assertThat(result.getJavaClasses()).hasSize(1);
        assertThat(result.getJavaClasses().get(0).getQualifiedName()).isEqualTo("DefaultPackageClass");
        assertThat(result.getJavaClasses().get(0).getPackageName()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Method with multiple parameter types
    // ------------------------------------------------------------------

    @Test
    void parseMethodWithMultipleParams() {
        String source = """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public class Complex {
                    public Map<String, List<Integer>> transform(
                            List<String> input, int limit, boolean ascending) {
                        return null;
                    }
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("Complex.java"), source);

        JavaMethodModel method = result.getJavaClasses().get(0).getMethods().get(0);
        assertThat(method.getMethodName()).isEqualTo("transform");
        assertThat(method.getReturnType()).isEqualTo("Map<String,List<Integer>>");
        assertThat(method.getParameterTypes()).hasSize(3);
        assertThat(method.getParameterTypes().get(0)).isEqualTo("List<String>");
        assertThat(method.getParameterTypes().get(1)).isEqualTo("int");
        assertThat(method.getParameterTypes().get(2)).isEqualTo("boolean");
    }

    // ------------------------------------------------------------------
    // Annotation with member-value pairs
    // ------------------------------------------------------------------

    @Test
    void parseAnnotationWithMemberValuePairs() {
        String source = """
                package com.example;

                @interface MyAnnotation {
                    String name();
                    int priority() default 0;
                }

                @MyAnnotation(name = "test", priority = 5)
                public class Annotated {
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("Annotated.java"), source);

        // Find the Annotated class (not the annotation declaration)
        JavaClassModel annotated = result.getJavaClasses().stream()
                .filter(c -> c.getClassName().equals("Annotated"))
                .findFirst()
                .orElseThrow();

        assertThat(annotated.getAnnotations()).hasSize(1);
        JavaAnnotationModel ann = annotated.getAnnotations().get(0);
        assertThat(ann.getAnnotationType()).isEqualTo("MyAnnotation");
        assertThat(ann.getValues()).containsEntry("name", "\"test\"");
        assertThat(ann.getValues()).containsEntry("priority", "5");
    }

    // ------------------------------------------------------------------
    // Static imports
    // ------------------------------------------------------------------

    @Test
    void parseStaticImports() {
        String source = """
                package com.example;

                import static java.util.Collections.emptyList;
                import static java.util.Objects.*;

                public class StaticImports {
                }
                """;

        JavaSourceFileModel result = parser.parse(Path.of("StaticImports.java"), source);

        assertThat(result.getImports()).containsExactly(
                "static java.util.Collections.emptyList",
                "static java.util.Objects.*"
        );
    }

    // ------------------------------------------------------------------
    // TypeReferenceCollector tests
    // ------------------------------------------------------------------

    @Test
    void typeReferenceCollector_collectsImportReferences() {
        String source = """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public class Foo {
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("java.util.List");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.IMPORT);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("java.util.Map");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.IMPORT);
        });
    }

    @Test
    void typeReferenceCollector_collectsInheritance() {
        String source = """
                package com.example;

                public class Child extends Parent implements Runnable {
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("Parent");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.INHERITANCE);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("Runnable");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.IMPLEMENTS_TYPE);
        });
    }

    @Test
    void typeReferenceCollector_collectsAnnotations() {
        String source = """
                package com.example;

                @Deprecated
                public class OldClass {
                    @Override
                    public String toString() { return "old"; }
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("Deprecated");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.ANNOTATION);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("Override");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.ANNOTATION);
        });
    }

    @Test
    void typeReferenceCollector_collectsFieldAndMethodReferences() {
        String source = """
                package com.example;

                import java.util.List;

                public class WithFields {
                    private List<String> items;

                    public String getName() { return "name"; }
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("List<String>");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.FIELD_DECLARATION);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("String");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.RETURN_TYPE);
        });
    }

    @Test
    void typeReferenceCollector_collectsConstructorCalls() {
        String source = """
                package com.example;

                import java.util.ArrayList;

                public class WithConstructor {
                    public void doWork() {
                        ArrayList<String> list = new ArrayList<>();
                    }
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.CONSTRUCTOR_CALL);
        });
    }

    @Test
    void typeReferenceCollector_collectsExceptionReferences() {
        String source = """
                package com.example;

                public class WithExceptions {
                    public void riskyMethod() throws IllegalStateException {
                        try {
                            throw new RuntimeException("oops");
                        } catch (RuntimeException e) {
                            // handle
                        }
                    }
                }
                """;

        List<JavaClassReference> refs = collectReferences(source);

        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("IllegalStateException");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.THROWS_METHOD_DECLARATION);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("RuntimeException");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.THROW_STATEMENT);
        });
        assertThat(refs).anySatisfy(ref -> {
            assertThat(ref.getQualifiedName()).isEqualTo("RuntimeException");
            assertThat(ref.getReferenceType()).isEqualTo(ReferenceType.CATCH_EXCEPTION_STATEMENT);
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<JavaClassReference> collectReferences(String source) {
        ASTParser astParser = ASTParser.newParser(AST.JLS17);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        astParser.setSource(source.toCharArray());
        astParser.setCompilerOptions(Map.of(
                "org.eclipse.jdt.core.compiler.source", "17",
                "org.eclipse.jdt.core.compiler.compliance", "17",
                "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"
        ));
        astParser.setResolveBindings(false);

        CompilationUnit cu = (CompilationUnit) astParser.createAST(null);

        TypeReferenceCollector collector = new TypeReferenceCollector(cu, null);
        cu.accept(collector);
        return collector.getReferences();
    }
}

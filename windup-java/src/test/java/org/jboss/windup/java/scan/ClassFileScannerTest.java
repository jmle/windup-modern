package org.jboss.windup.java.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link ClassFileScanner}. Each test compiles a small Java source
 * file at test time using {@link JavaCompiler} and then scans the resulting
 * {@code .class} file to verify that the scanner correctly extracts class
 * metadata and type references from the constant pool.
 */
class ClassFileScannerTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Basic class scanning
    // ------------------------------------------------------------------

    @Test
    void scanExtractsClassNameAndSuperclass() throws Exception {
        Path classFile = compileSource("SimpleClass", """
                package com.example;

                public class SimpleClass {
                    public void doSomething() { }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        assertThat(result.className()).isEqualTo("com.example.SimpleClass");
        assertThat(result.superClassName()).isEqualTo("java.lang.Object");
        assertThat(result.interfaces()).isEmpty();
    }

    @Test
    void scanExtractsInterfaces() throws Exception {
        Path classFile = compileSource("MyRunnable", """
                package com.example;

                public class MyRunnable implements Runnable, java.io.Serializable {
                    public void run() { }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        assertThat(result.className()).isEqualTo("com.example.MyRunnable");
        assertThat(result.superClassName()).isEqualTo("java.lang.Object");
        assertThat(result.interfaces())
                .containsExactlyInAnyOrder("java.lang.Runnable", "java.io.Serializable");
    }

    @Test
    void scanExtractsSuperclass() throws Exception {
        Path classFile = compileSource("MyList", """
                package com.example;

                public class MyList extends java.util.ArrayList<String> {
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        assertThat(result.className()).isEqualTo("com.example.MyList");
        assertThat(result.superClassName()).isEqualTo("java.util.ArrayList");
    }

    // ------------------------------------------------------------------
    // Referenced classes
    // ------------------------------------------------------------------

    @Test
    void scanExtractsReferencedClasses() throws Exception {
        // Note: we must actually instantiate types so the compiler emits
        // CONSTANT_Class entries for them. Merely declaring a field of a type
        // may only produce a descriptor string in the field_info, not a
        // CONSTANT_Class constant pool entry.
        Path classFile = compileSource("WithReferences", """
                package com.example;

                import java.util.HashMap;
                import java.util.List;

                public class WithReferences {
                    public String process(List<String> items) {
                        HashMap<String, String> map = new HashMap<>();
                        StringBuilder sb = new StringBuilder();
                        for (String item : items) {
                            map.put(item, item);
                            sb.append(item);
                        }
                        return sb.toString();
                    }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        assertThat(result.referencedClasses())
                .contains("java.util.HashMap", "java.lang.StringBuilder", "java.lang.String");
    }

    // ------------------------------------------------------------------
    // Method references
    // ------------------------------------------------------------------

    @Test
    void scanExtractsMethodReferences() throws Exception {
        Path classFile = compileSource("WithMethodCalls", """
                package com.example;

                public class WithMethodCalls {
                    public void doWork() {
                        String s = "hello";
                        int len = s.length();
                        String upper = s.toUpperCase();
                    }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        Set<String> methodNames = result.methodReferences().stream()
                .map(ClassFileScanResult.MethodReference::methodName)
                .collect(Collectors.toSet());

        assertThat(methodNames).contains("length", "toUpperCase");
    }

    // ------------------------------------------------------------------
    // Field references
    // ------------------------------------------------------------------

    @Test
    void scanExtractsFieldReferences() throws Exception {
        Path classFile = compileSource("WithFieldAccess", """
                package com.example;

                public class WithFieldAccess {
                    public void printOut() {
                        System.out.println("hello");
                    }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        ClassFileScanResult result = ClassFileScanner.scan(classFile);

        Set<String> fieldNames = result.fieldReferences().stream()
                .map(ClassFileScanResult.FieldReference::fieldName)
                .collect(Collectors.toSet());

        assertThat(fieldNames).contains("out");

        // Verify the field owner class
        assertThat(result.fieldReferences())
                .anyMatch(f -> f.className().equals("java.lang.System") && f.fieldName().equals("out"));
    }

    // ------------------------------------------------------------------
    // Byte array scanning
    // ------------------------------------------------------------------

    @Test
    void scanFromByteArray() throws Exception {
        Path classFile = compileSource("ByteArrayTest", """
                package com.example;

                public class ByteArrayTest {
                    public String greet() { return "hi"; }
                }
                """);
        assumeTrue(classFile != null, "javac not available");

        byte[] bytes = Files.readAllBytes(classFile);
        ClassFileScanResult result = ClassFileScanner.scan(bytes);

        assertThat(result.className()).isEqualTo("com.example.ByteArrayTest");
        assertThat(result.superClassName()).isEqualTo("java.lang.Object");
    }

    // ------------------------------------------------------------------
    // Error cases
    // ------------------------------------------------------------------

    @Test
    void scanThrowsOnInvalidFile() throws Exception {
        Path notAClassFile = tempDir.resolve("NotAClass.class");
        Files.writeString(notAClassFile, "this is not a class file");

        assertThatThrownBy(() -> ClassFileScanner.scan(notAClassFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a valid class file");
    }

    @Test
    void scanThrowsOnNonexistentFile() {
        Path missing = tempDir.resolve("DoesNotExist.class");

        assertThatThrownBy(() -> ClassFileScanner.scan(missing))
                .isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // Internal name conversion
    // ------------------------------------------------------------------

    @Test
    void internalNameToQualifiedConvertsSlashesToDots() {
        assertThat(ClassFileScanner.internalNameToQualified("com/example/Foo"))
                .isEqualTo("com.example.Foo");
        assertThat(ClassFileScanner.internalNameToQualified("java/lang/Object"))
                .isEqualTo("java.lang.Object");
        assertThat(ClassFileScanner.internalNameToQualified("Standalone"))
                .isEqualTo("Standalone");
    }

    @Test
    void internalNameToQualifiedHandlesNull() {
        assertThat(ClassFileScanner.internalNameToQualified(null))
                .isEqualTo("<unknown>");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Compiles a Java source string and returns the path to the resulting
     * {@code .class} file, or {@code null} if no compiler is available.
     *
     * @param className the simple class name (must match the public class in source)
     * @param source    the full Java source code
     */
    private Path compileSource(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return null;
        }

        // Create package directory structure
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        int result = compiler.run(null, null, null,
                "-d", tempDir.toString(),
                sourceFile.toString());

        if (result != 0) {
            return null;
        }

        // The class is in package com.example, so look there
        Path classFile = tempDir.resolve("com/example/" + className + ".class");
        if (Files.exists(classFile)) {
            return classFile;
        }

        // Fall back to root if no package
        return tempDir.resolve(className + ".class");
    }
}

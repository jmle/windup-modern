package org.jboss.windup.java.decompiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the {@link FallbackDecompiler} which uses {@code javap} from the
 * JDK to produce a disassembly listing of {@code .class} files.
 */
class FallbackDecompilerTest {

    @TempDir
    Path tempDir;

    @Test
    void decompileProducesOutputContainingClassAndMethodSignatures() throws Exception {
        Path classFile = compileSimpleClass();
        assumeTrue(classFile != null, "javac not available in this JDK");

        FallbackDecompiler decompiler = new FallbackDecompiler();
        Optional<String> result = decompiler.decompile(classFile);

        assertThat(result).isPresent();
        String output = result.get();

        // javap output should contain the class name and method signatures
        assertThat(output).contains("SampleClass");
        assertThat(output).contains("greet");
        // javap -p shows private members
        assertThat(output).contains("getMessage");
    }

    @Test
    void decompileReturnsEmptyForNonexistentFile() {
        FallbackDecompiler decompiler = new FallbackDecompiler();
        Optional<String> result = decompiler.decompile(tempDir.resolve("DoesNotExist.class"));

        assertThat(result).isEmpty();
    }

    @Test
    void decompileReturnsEmptyForNullPath() {
        FallbackDecompiler decompiler = new FallbackDecompiler();
        Optional<String> result = decompiler.decompile(null);

        assertThat(result).isEmpty();
    }

    @Test
    void classNameFromZipEntryConversion() {
        assertThat(FallbackDecompiler.classNameFromZipEntry("/com/example/Foo.class"))
                .isEqualTo("com.example.Foo");
        assertThat(FallbackDecompiler.classNameFromZipEntry("org/acme/Bar.class"))
                .isEqualTo("org.acme.Bar");
        assertThat(FallbackDecompiler.classNameFromZipEntry("/Standalone.class"))
                .isEqualTo("Standalone");
    }

    /**
     * Compiles a simple Java source file using the system {@link JavaCompiler}
     * and returns the path to the resulting {@code .class} file.
     *
     * @return the path to the compiled class file, or {@code null} if no
     *         compiler is available (e.g. running on a JRE instead of a JDK)
     */
    private Path compileSimpleClass() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return null;
        }

        String source = """
                public class SampleClass {
                    public String greet(String name) {
                        return "Hello, " + name;
                    }
                    private String getMessage() {
                        return "private message";
                    }
                }
                """;

        Path sourceFile = tempDir.resolve("SampleClass.java");
        Files.writeString(sourceFile, source);

        int result = compiler.run(null, null, null,
                "-d", tempDir.toString(),
                sourceFile.toString());

        if (result != 0) {
            return null;
        }

        return tempDir.resolve("SampleClass.class");
    }
}

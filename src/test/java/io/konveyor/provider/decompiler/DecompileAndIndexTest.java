package io.konveyor.provider.decompiler;

import io.konveyor.provider.index.IndexedSymbol;
import io.konveyor.provider.index.LocationType;
import io.konveyor.provider.index.SymbolIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the decompile-then-index pipeline: compiles Java source to a JAR,
 * decompiles it, indexes the output as dependency source, and verifies that symbols are
 * queryable and correctly marked as dependency files.
 */
class DecompileAndIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDecompileJarAndIndexSymbols() throws Exception {
        Path jar = createTestJar();
        Path decompiledDir = tempDir.resolve("decompiled");

        VineflowerDecompiler decompiler = new VineflowerDecompiler(1);
        DecompileResult result = decompiler.decompileJar(jar, decompiledDir);
        assertThat(result.hasOutput()).isTrue();

        SymbolIndex index = new SymbolIndex();
        index.indexDependencyDirectory(decompiledDir);

        assertThat(index.size()).isGreaterThan(0);

        List<IndexedSymbol> classes = index.query("com.example.dep.DepService", LocationType.CLASS);
        assertThat(classes).isNotEmpty();

        for (IndexedSymbol sym : classes) {
            assertThat(index.isDependencyFile(sym.fileUri())).isTrue();
        }
    }

    @Test
    void shouldDistinguishAppFromDependencySymbols() throws Exception {
        Path appSrcDir = tempDir.resolve("app-src/com/example/app");
        Files.createDirectories(appSrcDir);
        Files.writeString(appSrcDir.resolve("App.java"), """
                package com.example.app;
                import com.example.dep.DepService;
                public class App {
                    public void run() {
                        DepService svc = null;
                    }
                }
                """);

        Path jar = createTestJar();
        Path decompiledDir = tempDir.resolve("decompiled");
        new VineflowerDecompiler(1).decompileJar(jar, decompiledDir);

        SymbolIndex index = new SymbolIndex();
        index.indexDirectory(tempDir.resolve("app-src"));
        index.indexDependencyDirectory(decompiledDir);

        List<IndexedSymbol> allImports = index.query("com.example.dep.DepService", LocationType.IMPORT);
        assertThat(allImports).isNotEmpty();

        boolean hasAppImport = allImports.stream()
                .anyMatch(s -> !index.isDependencyFile(s.fileUri()));
        boolean hasDepImport = allImports.stream()
                .anyMatch(s -> index.isDependencyFile(s.fileUri()));

        assertThat(hasAppImport).as("App file should not be marked as dependency").isTrue();
    }

    private Path createTestJar() throws Exception {
        Path sourceDir = tempDir.resolve("jar-src/com/example/dep");
        Files.createDirectories(sourceDir);

        Files.writeString(sourceDir.resolve("DepService.java"), """
                package com.example.dep;
                public class DepService {
                    public String serve() {
                        return "served";
                    }
                }
                """);

        Files.writeString(sourceDir.resolve("DepUtil.java"), """
                package com.example.dep;
                import java.util.List;
                public class DepUtil {
                    public static List<String> transform(List<String> input) {
                        return input;
                    }
                }
                """);

        Path classesDir = tempDir.resolve("jar-classes");
        Files.createDirectories(classesDir);

        new ProcessBuilder("javac", "-d", classesDir.toString(),
                sourceDir.resolve("DepService.java").toString(),
                sourceDir.resolve("DepUtil.java").toString())
                .redirectErrorStream(true).start().waitFor();

        Path jarPath = tempDir.resolve("dep.jar");
        new ProcessBuilder("jar", "cf", jarPath.toString(), "-C", classesDir.toString(), ".")
                .redirectErrorStream(true).start().waitFor();

        return jarPath;
    }
}

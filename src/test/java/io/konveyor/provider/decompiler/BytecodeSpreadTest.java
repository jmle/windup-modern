package io.konveyor.provider.decompiler;

import io.konveyor.provider.index.IndexedSymbol;
import io.konveyor.provider.index.LocationType;
import io.konveyor.provider.index.SymbolIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bytecode-version spread (MTA-8159 compatibility matrix): the provider analyzes binaries by decompiling
 * {@code .class} bytecode back to {@code .java} and indexing it, so it must read bytecode from the whole
 * range of Java versions apps are built with. This compiles the SAME source at multiple {@code --release}
 * levels (Java 8 … 21), decompiles + indexes each, and asserts the provider finds the SAME symbols
 * regardless of bytecode version — i.e. a legacy Java-8 jar and a modern Java-21 jar look identical.
 *
 * <p>Adaptive: only tests releases the running JDK can actually target (a JDK can't emit a newer release
 * than itself), so it runs on the JDK-17 CI (8, 17) and locally on 21 (8, 17, 21). Needs {@code javac}/
 * {@code jar} on PATH, like {@code DecompileAndIndexTest}.</p>
 */
class BytecodeSpreadTest {

    @TempDir
    Path tempDir;

    /** Java-8-compatible source compiled at every release level (its symbols must be version-invariant). */
    private static final String BASE_SRC = """
            package com.example.app;
            public class Base {}
            """;
    private static final String SERVICE_SRC = """
            package com.example.app;
            import java.util.List;
            public class Service extends Base {
                private List<String> items;
                public List<String> getItems() { return items; }
            }
            """;

    @Test
    void sameSymbolsAcrossBytecodeVersions() throws Exception {
        int maxFeature = Runtime.version().feature();
        List<Integer> releases = Stream.of(8, 17, 21).filter(r -> r <= maxFeature).toList();
        assertThat(releases).as("need >=2 release levels to compare").hasSizeGreaterThanOrEqualTo(2);

        Set<String> reference = null;
        for (int release : releases) {
            Path jar = compileJar(release, Map.of("Base.java", BASE_SRC, "Service.java", SERVICE_SRC));
            SymbolIndex index = decompileAndIndex(jar, "r" + release);

            Set<String> signature = new LinkedHashSet<>();
            if (!index.query("com.example.app.Service", LocationType.TYPE).isEmpty()) signature.add("TYPE:Service");
            if (!index.query("com.example.app.Base", LocationType.TYPE).isEmpty()) signature.add("TYPE:Base");
            if (!index.query("com.example.app.Base", LocationType.INHERITANCE).isEmpty()) signature.add("INH:Base");
            if (!index.query("java.util.List", LocationType.IMPORT).isEmpty()) signature.add("IMPORT:List");

            // core symbols must be present at every bytecode level (non-vacuous)
            assertThat(signature).as("release %d core symbols", release)
                    .contains("TYPE:Service", "TYPE:Base", "INH:Base");

            if (reference == null) reference = signature;
            else assertThat(signature).as("release %d symbols match the baseline", release).isEqualTo(reference);
        }
    }

    @Test
    void modernRecordBytecodeDecompilesAndIndexes() throws Exception {
        // records are a Java 16+ construct; exercise modern bytecode through decompile -> index.
        if (Runtime.version().feature() < 16) return;
        Path jar = compileJar(17, Map.of("Point.java", """
                package com.example.app;
                public record Point(int x, int y) {}
                """));
        SymbolIndex index = decompileAndIndex(jar, "rec");
        assertThat(index.query("com.example.app.Point", LocationType.TYPE))
                .as("record type indexed from Java-17 bytecode").isNotEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private Path compileJar(int release, Map<String, String> sources) throws Exception {
        Path srcDir = tempDir.resolve("src-" + release + "/com/example/app");
        Files.createDirectories(srcDir);
        List<String> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        Path classes = tempDir.resolve("classes-" + release);
        Files.createDirectories(classes);
        List<String> javac = new ArrayList<>(List.of("javac", "--release", String.valueOf(release),
                "-d", classes.toString()));
        javac.addAll(files);
        run(javac);

        Path jar = tempDir.resolve("app-" + release + ".jar");
        run(List.of("jar", "cf", jar.toString(), "-C", classes.toString(), "."));
        return jar;
    }

    private SymbolIndex decompileAndIndex(Path jar, String tag) throws Exception {
        Path out = tempDir.resolve("decompiled-" + tag);
        DecompileResult result = new VineflowerDecompiler(1).decompileJar(jar, out);
        assertThat(result.hasOutput()).as("decompiler produced output for %s", tag).isTrue();
        SymbolIndex index = new SymbolIndex();
        index.indexDependencyDirectory(out);
        assertThat(index.size()).as("indexed symbols for %s", tag).isGreaterThan(0);
        return index;
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("command failed: " + String.join(" ", cmd) + "\n" + output);
        }
    }
}

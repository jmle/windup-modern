package io.konveyor.provider.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeatable micro-benchmark for the symbol index (MTA-8157 Phase 3). Generates a synthetic Java corpus,
 * then measures indexing time, peak-ish heap, and query latency. Opt-in (perf numbers are noisy and
 * environment-specific, so this is NOT part of the normal gate):
 *
 * <pre>mvn -Dperf=true -Dtest=PerfBenchmarkTest test</pre>
 *
 * Results are printed and written to {@code target/perf-report.txt}. See
 * {@code testing/PERFORMANCE.md} for the procedure, baselines, and how to read them.
 *
 * <p><b>Regression gate (opt-in):</b> with {@code -Dperf.gate=true} the benchmark additionally asserts
 * the measured metrics stay under generous ceilings. The ceilings are deliberately loose — several times
 * the local baseline — so they tolerate noisy, slower CI runners and only trip on <i>gross</i> (roughly
 * order-of-magnitude / algorithmic) regressions, never small deltas. This is why the gate runs nightly
 * (see {@code nightly-benchmarks.yaml}), not per-PR. Override any ceiling with its system property.</p>
 */
@EnabledIfSystemProperty(named = "perf", matches = "true")
class PerfBenchmarkTest {

    @TempDir
    Path tempDir;

    private static final int FILES = Integer.getInteger("perf.files", 2000);
    private static final int QUERIES = Integer.getInteger("perf.queries", 5000);

    /** When true, assert the metrics stay under the ceilings below (gross-regression gate). */
    private static final boolean GATE = Boolean.getBoolean("perf.gate");

    // Ceilings default to several times the documented local baseline (0.27 ms/file, p95 ~0.61 ms,
    // ~13 MB heap) so slower/noisy CI runners pass but an order-of-magnitude regression fails.
    private static final double MAX_MS_PER_FILE = Double.parseDouble(System.getProperty("perf.budget.msPerFile", "3.0"));
    private static final double MAX_P95_US = Double.parseDouble(System.getProperty("perf.budget.p95Us", "5000"));
    private static final long MAX_HEAP_MB = Long.getLong("perf.budget.heapMb", 256L);

    @Test
    void benchmarkIndexAndQuery() throws IOException {
        Path root = generateCorpus(FILES);

        SymbolIndex index = new SymbolIndex();
        long t0 = System.nanoTime();
        index.indexDirectory(root);
        long indexMs = (System.nanoTime() - t0) / 1_000_000;
        int symbols = index.size();

        long heapMb = usedHeapMb();

        // Warm up, then time a batch of mixed queries.
        for (int i = 0; i < 200; i++) index.query("com.example.pkg0.Type0", LocationType.ANNOTATION);
        long[] latenciesNs = new long[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            String pat = "com.example.pkg" + (i % 50) + ".Type" + (i % 50);
            LocationType loc = (i % 2 == 0) ? LocationType.ANNOTATION : LocationType.TYPE;
            long q0 = System.nanoTime();
            index.query(pat, loc);
            latenciesNs[i] = System.nanoTime() - q0;
        }
        java.util.Arrays.sort(latenciesNs);
        double p50 = latenciesNs[QUERIES / 2] / 1_000.0;
        double p95 = latenciesNs[(int) (QUERIES * 0.95)] / 1_000.0;
        double max = latenciesNs[QUERIES - 1] / 1_000.0;

        String report = """
                === Symbol-index micro-benchmark ===
                files indexed      : %d
                symbols indexed    : %d
                index time         : %d ms  (%.2f ms/file)
                used heap after idx: %d MB
                queries            : %d
                query latency (us) : p50=%.1f  p95=%.1f  max=%.1f
                """.formatted(FILES, symbols, indexMs, (double) indexMs / FILES,
                heapMb, QUERIES, p50, p95, max);

        System.out.println(report);
        Path out = Path.of("target", "perf-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        if (GATE) {
            double msPerFile = (double) indexMs / FILES;
            // Guard against an empty/degenerate index silently passing the throughput check.
            assertThat(symbols)
                    .as("index produced symbols (sanity)").isPositive();
            assertThat(msPerFile)
                    .as("index throughput ms/file (ceiling %s; baseline ~0.27)", MAX_MS_PER_FILE)
                    .isLessThan(MAX_MS_PER_FILE);
            assertThat(p95)
                    .as("query p95 latency us (ceiling %s; baseline ~610)", MAX_P95_US)
                    .isLessThan(MAX_P95_US);
            assertThat(heapMb)
                    .as("used heap MB after index (ceiling %s; baseline ~13)", MAX_HEAP_MB)
                    .isLessThanOrEqualTo(MAX_HEAP_MB);
        }
    }

    private Path generateCorpus(int n) throws IOException {
        List<String> annotations = List.of("javax.ejb.Singleton", "javax.persistence.Entity",
                "org.springframework.stereotype.Service", "javax.inject.Inject");
        for (int i = 0; i < n; i++) {
            String pkg = "com/example/pkg" + (i % 50);
            Path dir = tempDir.resolve(pkg);
            Files.createDirectories(dir);
            String ann = annotations.get(i % annotations.size());
            String simple = ann.substring(ann.lastIndexOf('.') + 1);
            Files.writeString(dir.resolve("Type" + i + ".java"), """
                    package com.example.pkg%d;

                    import %s;
                    import java.util.List;

                    @%s
                    public class Type%d {
                        private String field%d;
                        public List<String> method%d() { return null; }
                    }
                    """.formatted(i % 50, ann, simple, i, i, i));
        }
        return tempDir;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }
}

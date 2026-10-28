package io.konveyor.provider;

import io.grpc.stub.StreamObserver;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.EvaluateRequest;
import io.konveyor.provider.grpc.InitResponse;
import io.konveyor.provider.grpc.ServiceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soak test (MTA-8157 Phase 4): churn many Init -> evaluate -> Stop cycles through one long-lived
 * {@link JavaProviderService} (as a Hub-hosted provider does, app after app) and assert nothing leaks:
 * the workspace registry returns to empty, threads stay bounded, and heap/RSS do not climb without bound.
 *
 * Opt-in (long-running); process RSS sampling is Linux-only:
 * <pre>mvn -Dsoak=true -Dtest=SoakTest test   # tune: -Dsoak.cycles=500</pre>
 * A report is written to {@code target/soak-report.txt}.
 */
@EnabledIfSystemProperty(named = "soak", matches = "true")
class SoakTest {

    @TempDir
    Path tempDir;

    private static final int CYCLES = Integer.getInteger("soak.cycles", 300);
    private static final int CORPUS = Integer.getInteger("soak.files", 100);

    @Test
    void manyInitStopCyclesDoNotLeak() throws Exception {
        Path app = generateCorpus();
        JavaProviderService svc = new JavaProviderService(10);

        long heapAfterWarmup = 0, rssAfterWarmup = 0;
        int threadsAfterWarmup = 0;
        long peakHeap = 0, peakRss = 0;
        int peakThreads = 0;
        StringBuilder trend = new StringBuilder("cycle,heapMB,rssMB,threads,liveWorkspaces\n");

        for (int i = 1; i <= CYCLES; i++) {
            long id = initSourceOnly(svc, app);
            // a couple of representative queries
            evaluate(svc, id, "javax.ejb.Singleton", "ANNOTATION");
            evaluate(svc, id, "com.example.pkg0.Type0", "TYPE");
            svc.stop(ServiceRequest.newBuilder().setId(id).build(), ignore());

            if (i % 25 == 0 || i == CYCLES) {
                long heap = usedHeapMb();
                long rss = rssMb();
                int threads = ManagementFactory.getThreadMXBean().getThreadCount();
                trend.append("%d,%d,%d,%d,%d%n".formatted(i, heap, rss, threads, svc.activeWorkspaces()));
                peakHeap = Math.max(peakHeap, heap);
                peakRss = Math.max(peakRss, rss);
                peakThreads = Math.max(peakThreads, threads);
                if (i == 50) { // baseline after warmup
                    heapAfterWarmup = heap;
                    rssAfterWarmup = rss;
                    threadsAfterWarmup = threads;
                }
            }
        }

        long heapEnd = usedHeapMb();
        long rssEnd = rssMb();
        int threadsEnd = ManagementFactory.getThreadMXBean().getThreadCount();
        String report = """
                === Soak: %d Init/evaluate/Stop cycles, %d-file corpus ===
                heap  : warmup=%d MB  end=%d MB  peak=%d MB
                rss   : warmup=%d MB  end=%d MB  peak=%d MB
                threads: warmup=%d  end=%d  peak=%d
                live workspaces at end: %d

                %s""".formatted(CYCLES, CORPUS, heapAfterWarmup, heapEnd, peakHeap,
                rssAfterWarmup, rssEnd, peakRss, threadsAfterWarmup, threadsEnd, peakThreads,
                svc.activeWorkspaces(), trend);
        System.out.println(report);
        Path out = Path.of("target", "soak-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        // Hard leak gates (deterministic):
        assertThat(svc.activeWorkspaces()).as("every Init must be released by Stop").isZero();
        assertThat(threadsEnd).as("thread count must stay bounded (no per-cycle thread leak)")
                .isLessThanOrEqualTo(threadsAfterWarmup + 10);
        // Heap must not climb without bound (generous factor to tolerate GC/JIT noise).
        assertThat(heapEnd).as("heap must not grow unboundedly across cycles")
                .isLessThanOrEqualTo(Math.max(64, heapAfterWarmup * 2));
    }

    private Path generateCorpus() throws Exception {
        for (int i = 0; i < CORPUS; i++) {
            Path dir = tempDir.resolve("com/example/pkg" + (i % 10));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Type" + i + ".java"), """
                    package com.example.pkg%d;
                    import javax.ejb.Singleton;
                    @Singleton
                    public class Type%d { public String m%d() { return null; } }
                    """.formatted(i % 10, i, i));
        }
        return tempDir;
    }

    private static long initSourceOnly(JavaProviderService svc, Path location) {
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(location.toString())
                .setAnalysisMode("source-only").build(), capture(ref));
        return ref.get().getId();
    }

    private static void evaluate(JavaProviderService svc, long id, String pattern, String loc) {
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("referenced")
                .setConditionInfo("referenced:\n  pattern: '" + pattern + "'\n  location: " + loc + "\n")
                .build(), ignore());
    }

    private static long usedHeapMb() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /** Resident set size of this JVM (MB) from /proc; 0 if unavailable (non-Linux). */
    private static long rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static <T> StreamObserver<T> capture(AtomicReference<T> ref) {
        return new StreamObserver<>() {
            @Override public void onNext(T value) { ref.set(value); }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onCompleted() { }
        };
    }

    private static <T> StreamObserver<T> ignore() {
        return new StreamObserver<>() {
            @Override public void onNext(T value) { }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onCompleted() { }
        };
    }
}

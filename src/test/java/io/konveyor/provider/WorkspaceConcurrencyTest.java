package io.konveyor.provider;

import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.ProviderEvaluateResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 concurrency test (MTA-8157): the kantra engine fans rule evaluation out to a worker pool and
 * sends concurrent {@code Evaluate} calls to a single workspace. This hammers one indexed workspace from
 * many threads and asserts results are correct, consistent, and that nothing deadlocks — exercising the
 * thread-safety of the query path, the {@code ThreadLocal<Yaml>} parser, and the glob→regex cache.
 */
class WorkspaceConcurrencyTest {

    @TempDir
    static Path tempDir;

    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        Path src = tempDir.resolve("com/example/apps");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Bean.java"), """
                package com.example.apps;

                import javax.ejb.Singleton;
                import javax.ejb.SessionBean;

                @Singleton
                public class Bean implements SessionBean {
                    private String name;
                    public Bean() { this.name = "x"; }
                    public String getName() { return name; }
                }
                """);
        Files.writeString(src.resolve("Service.java"), """
                package com.example.apps;

                public class Service {
                    public Bean make() { return new Bean(); }
                }
                """);

        Config config = Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build();
        ctx = new WorkspaceContext(0, tempDir.toString(), "source-only", config, 10);
        ctx.index();
    }

    @Test
    void concurrentEvaluatesAreConsistentAndThreadSafe() throws Exception {
        int threads = 16;
        int callsPerThread = 60;

        // A few (pattern, location, mustMatch) probes with known, stable answers.
        record Probe(String pattern, String location, boolean mustMatch) {}
        List<Probe> probes = List.of(
                new Probe("javax.ejb.Singleton", "ANNOTATION", true),
                new Probe("javax.ejb.SessionBean", "IMPLEMENTS_TYPE", true),
                new Probe("com.example.apps.Bean", "CONSTRUCTOR_CALL", true),
                new Probe("com.example.apps.Bean", "IMPORT", false),
                new Probe("com.does.not.Exist", "TYPE", false));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger mismatches = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < callsPerThread; i++) {
                    for (Probe p : probes) {
                        ProviderEvaluateResponse r = ctx.evaluate("referenced", referenced(p.pattern(), p.location()));
                        if (r.getMatched() != p.mustMatch()) {
                            mismatches.incrementAndGet();
                        }
                    }
                }
                return null;
            });
        }

        List<Future<Void>> results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
        pool.shutdown();
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS);

        assertThat(finished).as("evaluation pool must finish (no deadlock)").isTrue();
        for (Future<Void> f : results) {
            f.get();  // surfaces any exception thrown on a worker thread
        }
        assertThat(mismatches.get())
                .as("every concurrent evaluate must return the same, correct result")
                .isZero();
    }

    private static String referenced(String pattern, String location) {
        return "referenced:\n  pattern: '" + pattern + "'\n  location: " + location + "\n";
    }
}

package io.konveyor.provider;

import io.grpc.stub.StreamObserver;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.EvaluateRequest;
import io.konveyor.provider.grpc.EvaluateResponse;
import io.konveyor.provider.grpc.InitResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Robustness tests (MTA-8158): malformed or hostile inputs must be handled gracefully — the provider is a
 * long-lived server, so a bad analysis must return an error (or degrade), never crash the process or take
 * down other workspaces.
 */
class ResilienceTest {

    @TempDir
    Path tempDir;

    @Test
    void initOnNonexistentLocationReturnsErrorNotCrash() {
        JavaProviderService svc = new JavaProviderService(10);
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        Config config = Config.newBuilder()
                .setLocation(tempDir.resolve("does-not-exist").toString())
                .setAnalysisMode("source-only")
                .build();
        svc.init(config, capture(ref));

        assertThat(ref.get()).as("init must return a response, not throw").isNotNull();
        assertThat(ref.get().getSuccessful()).isFalse();
        assertThat(ref.get().getError()).isNotEmpty();
    }

    @Test
    void initOnCorruptArchiveIsHandledGracefully() throws Exception {
        Path badJar = tempDir.resolve("corrupt.jar");
        Files.write(badJar, "this is not a valid zip".getBytes());

        JavaProviderService svc = new JavaProviderService(10);
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(badJar.toString())
                .setAnalysisMode("source-only").build(), capture(ref));

        // Robustness property: the server must not crash on a corrupt archive — it returns a response
        // (it degrades to an empty index rather than erroring, which is acceptable: no false results).
        assertThat(ref.get()).as("init must return a response for a corrupt archive, not crash").isNotNull();
    }

    @Test
    void malformedJavaFileDoesNotAbortIndexing() throws Exception {
        Path src = tempDir.resolve("com/example");
        Files.createDirectories(src);
        // one valid file
        Files.writeString(src.resolve("Good.java"), """
                package com.example;
                import javax.ejb.Singleton;
                @Singleton
                public class Good {}
                """);
        // one syntactically broken file
        Files.writeString(src.resolve("Broken.java"), "package com.example; this is not valid java {{{ ");

        JavaProviderService svc = new JavaProviderService(10);
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(tempDir.toString())
                .setAnalysisMode("source-only").build(), capture(ref));

        assertThat(ref.get().getSuccessful()).as("a broken file must not fail the whole analysis").isTrue();
        long id = ref.get().getId();

        // the valid file is still indexed and matchable
        AtomicReference<EvaluateResponse> eval = new AtomicReference<>();
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("referenced")
                .setConditionInfo("referenced:\n  pattern: 'javax.ejb.Singleton'\n  location: ANNOTATION\n")
                .build(), capture(eval));
        assertThat(eval.get().getSuccessful()).isTrue();
        assertThat(eval.get().getResponse().getMatched()).isTrue();
    }

    @Test
    void initOnTruncatedArchiveIsHandledGracefully() throws Exception {
        // valid zip, then truncated mid-stream
        Path jar = tempDir.resolve("truncated.jar");
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("a/b/C.txt"));
            zos.write(new byte[4096]);
            zos.closeEntry();
        }
        byte[] full = Files.readAllBytes(jar);
        Files.write(jar, java.util.Arrays.copyOf(full, full.length / 2));

        JavaProviderService svc = new JavaProviderService(10);
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(jar.toString()).setAnalysisMode("source-only").build(),
                capture(ref));
        assertThat(ref.get()).as("truncated archive must not crash the server").isNotNull();
    }

    @Test
    void initOnEmptyArchiveIsHandledGracefully() throws Exception {
        Path jar = tempDir.resolve("empty.jar");
        Files.write(jar, new byte[0]);

        JavaProviderService svc = new JavaProviderService(10);
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(jar.toString()).setAnalysisMode("source-only").build(),
                capture(ref));
        assertThat(ref.get()).as("zero-byte archive must not crash the server").isNotNull();
    }

    @Test
    void dependencyCapabilityWithoutBuildFileIsGraceful() {
        JavaProviderService svc = new JavaProviderService(10);
        long id = initSourceDir(svc);  // a source dir with no pom.xml / build.gradle

        AtomicReference<EvaluateResponse> eval = new AtomicReference<>();
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("dependency")
                .setConditionInfo("dependency:\n  name: org.example.anything\n").build(), capture(eval));

        assertThat(eval.get().getSuccessful()).isTrue();
        assertThat(eval.get().getResponse().getMatched()).isFalse();
    }

    @Test
    void malformedConditionInfoReturnsErrorNotCrash() {
        JavaProviderService svc = new JavaProviderService(10);
        long id = initSourceDir(svc);

        AtomicReference<EvaluateResponse> eval = new AtomicReference<>();
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("referenced")
                .setConditionInfo("referenced: [this is : not valid yaml").build(), capture(eval));

        assertThat(eval.get()).as("malformed conditionInfo must not crash").isNotNull();
        assertThat(eval.get().getSuccessful()).isFalse();
    }

    @Test
    void unknownCapabilityReturnsUnmatched() {
        JavaProviderService svc = new JavaProviderService(10);
        long id = initSourceDir(svc);

        AtomicReference<EvaluateResponse> eval = new AtomicReference<>();
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("bogus-capability")
                .setConditionInfo("whatever:\n").build(), capture(eval));

        assertThat(eval.get().getSuccessful()).isTrue();
        assertThat(eval.get().getResponse().getMatched()).isFalse();
    }

    /** Init a minimal source-only workspace (no build file) and return its id. */
    private long initSourceDir(JavaProviderService svc) {
        try {
            Path src = tempDir.resolve("app/com/example");
            Files.createDirectories(src);
            Files.writeString(src.resolve("A.java"), "package com.example; public class A {}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(Config.newBuilder().setLocation(tempDir.resolve("app").toString())
                .setAnalysisMode("source-only").build(), capture(ref));
        return ref.get().getId();
    }

    private static <T> StreamObserver<T> capture(AtomicReference<T> ref) {
        return new StreamObserver<>() {
            @Override public void onNext(T value) { ref.set(value); }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onCompleted() { }
        };
    }
}

package io.konveyor.provider;

import io.grpc.stub.StreamObserver;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.EvaluateRequest;
import io.konveyor.provider.grpc.EvaluateResponse;
import io.konveyor.provider.grpc.InitResponse;
import io.konveyor.provider.grpc.ServiceRequest;
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
 * Phase 1 resource-lifecycle tests (MTA-8157): Stop must release a workspace's resources — remove it
 * from the registry AND delete the temp dirs it created (decompiled {@code java-project/}, dependency
 * work dirs, mvn:// downloads) — so a long-lived provider doesn't leak disk across Init/Stop cycles.
 */
class WorkspaceLifecycleTest {

    @TempDir
    Path tempDir;

    /** Minimal .jar (one resource entry, no classes) so Init takes the archive path without needing javac. */
    private Path writeMinimalJar() throws Exception {
        Path jar = tempDir.resolve("app.jar");
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("README.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        return jar;
    }

    private static long init(JavaProviderService svc, Path location) {
        Config config = Config.newBuilder()
                .setLocation(location.toString())
                .setAnalysisMode("source-only")
                .build();
        AtomicReference<InitResponse> ref = new AtomicReference<>();
        svc.init(config, capture(ref));
        assertThat(ref.get().getSuccessful()).as("init failed: %s", ref.get().getError()).isTrue();
        return ref.get().getId();
    }

    @Test
    void stopDeletesDecompiledProjectDirAndRemovesWorkspace() throws Exception {
        JavaProviderService svc = new JavaProviderService(10);
        long id = init(svc, writeMinimalJar());

        // Init on an archive decompiles into <parent>/java-project
        Path projectDir = tempDir.resolve("java-project");
        assertThat(projectDir).as("Init should have created the decompiled project dir").exists();

        svc.stop(ServiceRequest.newBuilder().setId(id).build(), ignore());

        assertThat(projectDir).as("Stop must delete the decompiled project dir (disk leak otherwise)")
                .doesNotExist();

        // workspace is gone from the registry
        AtomicReference<EvaluateResponse> eval = new AtomicReference<>();
        svc.evaluate(EvaluateRequest.newBuilder().setId(id).setCap("referenced")
                .setConditionInfo("referenced:\n  pattern: 'x'\n").build(), capture(eval));
        assertThat(eval.get().getSuccessful()).isFalse();
        assertThat(eval.get().getError()).contains("unknown workspace");
    }

    @Test
    void repeatedInitStopDoesNotLeaveProjectDirs() throws Exception {
        JavaProviderService svc = new JavaProviderService(10);
        for (int i = 0; i < 10; i++) {
            long id = init(svc, writeMinimalJar());
            svc.stop(ServiceRequest.newBuilder().setId(id).build(), ignore());
        }
        assertThat(tempDir.resolve("java-project"))
                .as("no decompiled project dir should remain after Init/Stop cycles").doesNotExist();
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

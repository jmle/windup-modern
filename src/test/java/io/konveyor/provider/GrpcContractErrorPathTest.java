package io.konveyor.provider;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.konveyor.provider.grpc.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract/error-path coverage for {@link JavaProviderService} (MTA-8156). Complements
 * {@link GrpcIntegrationTest} (happy paths) by asserting the service degrades gracefully — it must
 * return {@code successful=false} responses (never crash the stream) for unknown workspaces, bad
 * Init locations, unknown capabilities and malformed conditions, and {@code Stop} must be an
 * idempotent no-op. Runs through the real in-process gRPC stack.
 */
class GrpcContractErrorPathTest {

    static final String SERVER_NAME = "test-error-path-provider";
    static final long UNKNOWN_ID = 999_999L;

    @TempDir
    static Path tempDir;

    static JavaProviderService providerService;
    static Server server;
    static ManagedChannel channel;
    static ProviderServiceGrpc.ProviderServiceBlockingStub stub;

    /** A workspace Init'd once over a tiny real project, reused by the cap/condition cases. */
    static long workspaceId;

    @BeforeAll
    static void setUp() throws Exception {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Sample.java"), """
                package com.example;
                import java.util.List;
                public class Sample {
                    private List<String> items;
                }
                """);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        providerService = new JavaProviderService(10);
        server = InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(providerService)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        stub = ProviderServiceGrpc.newBlockingStub(channel);

        InitResponse init = stub.init(Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build());
        assertThat(init.getSuccessful()).isTrue();
        workspaceId = init.getId();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---- unknown workspace id on every data RPC --------------------------------------------------

    @Test
    void getDependenciesFailsForUnknownWorkspace() {
        DependencyResponse resp = stub.getDependencies(ServiceRequest.newBuilder().setId(UNKNOWN_ID).build());
        assertThat(resp.getSuccessful()).isFalse();
        assertThat(resp.getError()).contains("unknown workspace");
    }

    @Test
    void getDependenciesDAGFailsForUnknownWorkspace() {
        DependencyDAGResponse resp =
                stub.getDependenciesDAG(ServiceRequest.newBuilder().setId(UNKNOWN_ID).build());
        assertThat(resp.getSuccessful()).isFalse();
        assertThat(resp.getError()).contains("unknown workspace");
    }

    @Test
    void evaluateFailsForUnknownWorkspace() {
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(UNKNOWN_ID).setCap("referenced").setConditionInfo("referenced:\n  pattern: X")
                .build());
        assertThat(resp.getSuccessful()).isFalse();
        assertThat(resp.getError()).contains("unknown workspace");
    }

    // ---- Stop is an idempotent no-op -------------------------------------------------------------

    @Test
    void stopIsNoOpForUnknownWorkspace() {
        // must not throw and must complete the stream
        Empty resp = stub.stop(ServiceRequest.newBuilder().setId(UNKNOWN_ID).build());
        assertThat(resp).isNotNull();
    }

    @Test
    void stopIsIdempotentAndFreesWorkspace() {
        InitResponse init = stub.init(Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build());
        assertThat(init.getSuccessful()).isTrue();
        long id = init.getId();
        int before = providerService.activeWorkspaces();

        stub.stop(ServiceRequest.newBuilder().setId(id).build());
        assertThat(providerService.activeWorkspaces()).isEqualTo(before - 1);

        // second Stop on the same id is a harmless no-op (no leak, no error)
        Empty second = stub.stop(ServiceRequest.newBuilder().setId(id).build());
        assertThat(second).isNotNull();
        assertThat(providerService.activeWorkspaces()).isEqualTo(before - 1);
    }

    // ---- Init failure path ----------------------------------------------------------------------

    @Test
    void initFailsGracefullyForMalformedMvnUriWithoutLeakingWorkspace() {
        int before = providerService.activeWorkspaces();
        // "mvn://foo" is recognised as an mvn:// URI but has too few coordinates -> parse throws,
        // synchronously, before any network access.
        InitResponse resp = stub.init(Config.newBuilder()
                .setLocation("mvn://foo")
                .setAnalysisMode("source-only")
                .build());
        assertThat(resp.getSuccessful()).isFalse();
        assertThat(resp.getError()).isNotEmpty();
        // a failed Init must not register a live workspace
        assertThat(providerService.activeWorkspaces()).isEqualTo(before);
        // and the returned id must not resolve to anything
        EvaluateResponse ev = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(resp.getId()).setCap("referenced").setConditionInfo("referenced:\n  pattern: X")
                .build());
        assertThat(ev.getSuccessful()).isFalse();
        assertThat(ev.getError()).contains("unknown workspace");
    }

    // ---- unknown capability + malformed condition on a live workspace ---------------------------

    @Test
    void evaluateUnknownCapabilityReturnsNoMatch() {
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId).setCap("bogus-capability").setConditionInfo("{}")
                .build());
        // unknown cap is handled gracefully: successful, just no match
        assertThat(resp.getSuccessful()).isTrue();
        assertThat(resp.getResponse().getMatched()).isFalse();
    }

    @Test
    void evaluateMalformedConditionFailsGracefullyAndServerStaysUp() {
        EvaluateResponse bad = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId).setCap("referenced")
                .setConditionInfo("referenced: [this: is: not: valid: yaml")
                .build());
        assertThat(bad.getSuccessful()).isFalse();
        assertThat(bad.getError()).isNotEmpty();

        // the workspace is still usable after a bad condition (no corrupted state)
        EvaluateResponse good = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId).setCap("referenced")
                .setConditionInfo("referenced:\n  location: IMPORT\n  pattern: java.util.List")
                .build());
        assertThat(good.getSuccessful()).isTrue();
    }

    // ---- no-op contract stubs -------------------------------------------------------------------

    @Test
    void notifyFileChangesAndPrepareCompleteWithoutError() {
        NotifyFileChangesResponse nfc = stub.notifyFileChanges(NotifyFileChangesRequest.newBuilder().build());
        assertThat(nfc).isNotNull();
        PrepareResponse prep = stub.prepare(PrepareRequest.newBuilder().build());
        assertThat(prep).isNotNull();
    }
}

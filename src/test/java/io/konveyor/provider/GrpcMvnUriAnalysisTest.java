package io.konveyor.provider;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.konveyor.provider.grpc.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end {@code mvn://} analysis (MTA-8159 compatibility matrix): Init the provider on an
 * {@code mvn://groupId:artifactId:version} location and confirm the full path — Maven resolve → decompile
 * → index → Evaluate — works. Runs **offline** against a temp local repo (a real jar built from source
 * and laid out in Maven layout), so it's deterministic and needs no network.
 */
class GrpcMvnUriAnalysisTest {

    static final String SERVER_NAME = "test-mvn-uri-provider";

    @TempDir
    static Path tempDir;

    static Server server;
    static ManagedChannel channel;
    static ProviderServiceGrpc.ProviderServiceBlockingStub stub;
    static long workspaceId;
    static String savedRepo;
    static String savedOffline;

    @BeforeAll
    static void setUp() throws Exception {
        installArtifact();  // com.example.lib:widget:1.0 -> temp local repo

        savedRepo = System.getProperty("maven.repo.local");
        savedOffline = System.getProperty("maven.offline");
        System.setProperty("maven.repo.local", tempDir.resolve("m2").toString());
        System.setProperty("maven.offline", "true");

        JavaProviderService providerService = new JavaProviderService(10);
        server = InProcessServerBuilder.forName(SERVER_NAME).directExecutor()
                .addService(providerService).build().start();
        channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        stub = ProviderServiceGrpc.newBlockingStub(channel);

        InitResponse init = stub.init(Config.newBuilder()
                .setLocation("mvn://com.example.lib:widget:1.0")
                .setAnalysisMode("full")
                .build());
        assertThat(init.getSuccessful()).as("Init on mvn:// must succeed: %s", init.getError()).isTrue();
        workspaceId = init.getId();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        restore("maven.repo.local", savedRepo);
        restore("maven.offline", savedOffline);
    }

    @Test
    void evaluatesTypeFromMvnArtifact() {
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId).setCap("referenced")
                .setConditionInfo("referenced:\n  location: TYPE\n  pattern: com.example.lib.Widget")
                .build());
        assertThat(resp.getSuccessful()).as("evaluate error: %s", resp.getError()).isTrue();
        assertThat(resp.getResponse().getMatched())
                .as("the mvn:// artifact's class must be decompiled + indexed").isTrue();
    }

    @Test
    void evaluatesImportFromMvnArtifact() {
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId).setCap("referenced")
                .setConditionInfo("referenced:\n  location: IMPORT\n  pattern: java.util.List")
                .build());
        assertThat(resp.getSuccessful()).isTrue();
        assertThat(resp.getResponse().getMatched()).isTrue();
    }

    // ── build com.example.lib:widget:1.0 into a temp local repo (Maven layout) ──────────────────────

    private static void installArtifact() throws Exception {
        Path src = tempDir.resolve("src/com/example/lib");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Widget.java"), """
                package com.example.lib;
                import java.util.List;
                public class Widget {
                    private List<String> parts;
                    public List<String> getParts() { return parts; }
                }
                """);
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        run("javac", "-d", classes.toString(), src.resolve("Widget.java").toString());

        Path artDir = tempDir.resolve("m2/com/example/lib/widget/1.0");
        Files.createDirectories(artDir);
        run("jar", "cf", artDir.resolve("widget-1.0.jar").toString(), "-C", classes.toString(), ".");
        Files.writeString(artDir.resolve("widget-1.0.pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example.lib</groupId>"
                        + "<artifactId>widget</artifactId><version>1.0</version></project>");
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("command failed: " + String.join(" ", cmd));
        }
    }

    private static void restore(String key, String val) {
        if (val == null) System.clearProperty(key);
        else System.setProperty(key, val);
    }
}

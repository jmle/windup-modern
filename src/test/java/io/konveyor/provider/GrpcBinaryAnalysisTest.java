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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end <b>binary analysis</b> coverage (MTA-8159 compatibility matrix): Init the provider on a
 * real {@code .war} with no source — only compiled {@code .class} files in {@code WEB-INF/classes} and
 * a dependency jar in {@code WEB-INF/lib} — then drive the full gRPC path (Init -> Evaluate ->
 * GetDependencies). This exercises archive explosion, Vineflower decompilation, indexing the decompiled
 * output, and dependency identification, through the actual gRPC stack (not just the unit-level
 * ArchiveHandler).
 *
 * <p>The WAR is built from source at test time with {@code javac}/{@code jar} (same approach as
 * {@code DecompileAndIndexTest}) — no binary is checked in and no network is needed.</p>
 */
class GrpcBinaryAnalysisTest {

    static final String SERVER_NAME = "test-binary-provider";

    @TempDir
    static Path tempDir;

    static Server server;
    static ManagedChannel channel;
    static ProviderServiceGrpc.ProviderServiceBlockingStub stub;
    static long workspaceId;

    @BeforeAll
    static void setUp() throws Exception {
        Path war = buildWar();

        JavaProviderService providerService = new JavaProviderService(10);
        server = InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(providerService)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        stub = ProviderServiceGrpc.newBlockingStub(channel);

        InitResponse init = stub.init(Config.newBuilder()
                .setLocation(war.toString())
                .setAnalysisMode("full")
                .build());
        assertThat(init.getSuccessful()).as("Init on a .war must succeed: %s", init.getError()).isTrue();
        workspaceId = init.getId();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void evaluatesInheritanceInDecompiledWarClasses() {
        // MyServlet (in WEB-INF/classes, decompiled) extends BaseServlet (from WEB-INF/lib)
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId)
                .setCap("referenced")
                .setConditionInfo("referenced:\n  location: INHERITANCE\n  pattern: com.example.dep.BaseServlet")
                .build());
        assertThat(resp.getSuccessful()).as("evaluate error: %s", resp.getError()).isTrue();
        assertThat(resp.getResponse().getMatched())
                .as("INHERITANCE of BaseServlet must be found in the decompiled WAR class").isTrue();
        assertThat(resp.getResponse().getIncidentContextsList()).isNotEmpty();
    }

    @Test
    void evaluatesTypeReferenceInDecompiledWarClasses() {
        EvaluateResponse resp = stub.evaluate(EvaluateRequest.newBuilder()
                .setId(workspaceId)
                .setCap("referenced")
                .setConditionInfo("referenced:\n  location: TYPE\n  pattern: com.example.app.MyServlet")
                .build());
        assertThat(resp.getSuccessful()).as("evaluate error: %s", resp.getError()).isTrue();
        assertThat(resp.getResponse().getMatched())
                .as("the application class itself must be indexed from the WAR").isTrue();
    }

    @Test
    void identifiesDependencyJarFromWebInfLib() {
        DependencyResponse resp = stub.getDependencies(ServiceRequest.newBuilder().setId(workspaceId).build());
        assertThat(resp.getSuccessful()).as("getDependencies error: %s", resp.getError()).isTrue();
        // the dependency jar bundled in WEB-INF/lib must be identified
        boolean foundDep = resp.getFileDepList().stream()
                .flatMap(fd -> fd.getList().getDepsList().stream())
                .anyMatch(d -> d.getName() != null && d.getName().contains("dep"));
        assertThat(foundDep).as("WEB-INF/lib/dep.jar must be identified as a dependency").isTrue();
    }

    // ---- build a from-source WAR: WEB-INF/classes/MyServlet.class + WEB-INF/lib/dep.jar ------------

    private static Path buildWar() throws Exception {
        // 1. dependency source + jar: com.example.dep.BaseServlet
        Path depSrc = tempDir.resolve("dep-src/com/example/dep");
        Files.createDirectories(depSrc);
        Files.writeString(depSrc.resolve("BaseServlet.java"), """
                package com.example.dep;
                public class BaseServlet {
                    public String handle() { return "ok"; }
                }
                """);
        Path depClasses = tempDir.resolve("dep-classes");
        Files.createDirectories(depClasses);
        run("javac", "-d", depClasses.toString(), depSrc.resolve("BaseServlet.java").toString());
        Path depJar = tempDir.resolve("dep.jar");
        run("jar", "cf", depJar.toString(), "-C", depClasses.toString(), ".");

        // 2. application class compiled against the dep jar
        Path appSrc = tempDir.resolve("app-src/com/example/app");
        Files.createDirectories(appSrc);
        Files.writeString(appSrc.resolve("MyServlet.java"), """
                package com.example.app;
                import com.example.dep.BaseServlet;
                public class MyServlet extends BaseServlet {
                    public void service() { handle(); }
                }
                """);
        Path appClasses = tempDir.resolve("app-classes");
        Files.createDirectories(appClasses);
        run("javac", "-cp", depJar.toString(), "-d", appClasses.toString(),
                appSrc.resolve("MyServlet.java").toString());

        // 3. assemble the WAR (no source inside — only compiled classes + the dep jar)
        Path war = tempDir.resolve("sample-app.war");
        try (OutputStream os = Files.newOutputStream(war); ZipOutputStream zos = new ZipOutputStream(os)) {
            addFile(zos, "WEB-INF/classes/com/example/app/MyServlet.class",
                    appClasses.resolve("com/example/app/MyServlet.class"));
            addFile(zos, "WEB-INF/lib/dep.jar", depJar);
        }
        return war;
    }

    private static void addFile(ZipOutputStream zos, String entryName, Path file) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(Files.readAllBytes(file));
        zos.closeEntry();
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("command failed: " + String.join(" ", cmd));
        }
    }
}

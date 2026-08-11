package io.konveyor.provider;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.konveyor.provider.grpc.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that starts the full gRPC server in-process and exercises the complete
 * provider lifecycle: Capabilities, Init, Evaluate (multiple location types), GetCodeSnip,
 * GetDependencies, and Stop. Uses real Java source fixtures parsed by the actual indexer.
 */
class GrpcIntegrationTest {

    static final String SERVER_NAME = "test-java-provider";

    @TempDir
    static Path tempDir;

    static Server server;
    static ManagedChannel channel;
    static ProviderServiceGrpc.ProviderServiceBlockingStub providerStub;
    static ProviderCodeLocationServiceGrpc.ProviderCodeLocationServiceBlockingStub codeSnipStub;

    @BeforeAll
    static void setUp() throws Exception {
        createTestProject();

        JavaProviderService providerService = new JavaProviderService(10);
        CodeSnipService codeSnipService = new CodeSnipService(10);

        server = InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(providerService)
                .addService(codeSnipService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(SERVER_NAME)
                .directExecutor()
                .build();

        providerStub = ProviderServiceGrpc.newBlockingStub(channel);
        codeSnipStub = ProviderCodeLocationServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static void createTestProject() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example/legacy");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("OldServlet.java"), """
                package com.example.legacy;

                import javax.servlet.http.HttpServlet;
                import javax.ejb.Stateless;

                @Stateless
                public class OldServlet extends HttpServlet {

                    private String config;

                    public void handleRequest() {
                        javax.naming.InitialContext ctx = null;
                    }

                    public String getConfig() {
                        return config;
                    }
                }
                """);

        Files.writeString(srcDir.resolve("LegacyService.java"), """
                package com.example.legacy;

                import javax.ejb.Singleton;
                import org.springframework.stereotype.Service;

                @Singleton
                @Service
                public class LegacyService {

                    private OldServlet servlet;

                    public void process() {
                        OldServlet s = new OldServlet();
                        s.handleRequest();
                    }
                }
                """);

        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>legacy-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.servlet</groupId>
                            <artifactId>javax.servlet-api</artifactId>
                            <version>4.0.1</version>
                        </dependency>
                        <dependency>
                            <groupId>javax.ejb</groupId>
                            <artifactId>javax.ejb-api</artifactId>
                            <version>3.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }

    @Test
    void shouldReturnCapabilities() {
        CapabilitiesResponse response = providerStub.capabilities(Empty.getDefaultInstance());

        List<String> names = response.getCapabilitiesList().stream()
                .map(Capability::getName)
                .toList();
        assertThat(names).containsExactlyInAnyOrder("referenced", "dependency");
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WorkspaceLifecycle {

        static long workspaceId;

        @Test
        @Order(1)
        void shouldInitWorkspace() {
            Config config = Config.newBuilder()
                    .setLocation(tempDir.resolve("src/main/java").toString())
                    .setAnalysisMode("source-only")
                    .build();

            InitResponse response = providerStub.init(config);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getId()).isGreaterThan(0);
            workspaceId = response.getId();
        }

        @Test
        @Order(2)
        void shouldEvaluateImportPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.*"
                              location: "IMPORT"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSizeGreaterThanOrEqualTo(2);

            for (IncidentContext incident : incidents) {
                assertThat(incident.getFileURI()).startsWith("file://");
                assertThat(incident.getLineNumber()).isGreaterThan(0);
                assertThat(incident.getIsDependencyIncident()).isFalse();

                String kind = incident.getVariables().getFieldsMap().get("kind").getStringValue();
                assertThat(kind).isEqualTo("Module");
            }
        }

        @Test
        @Order(3)
        void shouldEvaluateAnnotationPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.Stateless"
                              location: "ANNOTATION"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSize(1);

            IncidentContext incident = incidents.get(0);
            assertThat(incident.getFileURI()).contains("OldServlet.java");
            assertThat(incident.getVariables().getFieldsMap().get("name").getStringValue())
                    .isEqualTo("Stateless");
        }

        @Test
        @Order(4)
        void shouldEvaluateInheritancePattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.servlet.http.HttpServlet"
                              location: "INHERITANCE"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSize(1);
            assertThat(incidents.get(0).getFileURI()).contains("OldServlet.java");
            assertThat(incidents.get(0).getVariables().getFieldsMap().get("name").getStringValue())
                    .isEqualTo("OldServlet");
        }

        @Test
        @Order(5)
        void shouldEvaluateConstructorCallPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "com.example.legacy.OldServlet"
                              location: "CONSTRUCTOR_CALL"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSize(1);
            assertThat(incidents.get(0).getFileURI()).contains("LegacyService.java");
        }

        @Test
        @Order(6)
        void shouldEvaluateMethodDeclarationPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "com.example.legacy.OldServlet.handle*"
                              location: "METHOD"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSize(1);

            String name = incidents.get(0).getVariables().getFieldsMap().get("name").getStringValue();
            assertThat(name).isEqualTo("handleRequest");
        }

        @Test
        @Order(7)
        void shouldEvaluateReturnTypePattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "* com.example.legacy.OldServlet"
                              location: "METHOD"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
        }

        @Test
        @Order(8)
        void shouldEvaluateFieldPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "com.example.legacy.OldServlet"
                              location: "FIELD"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isTrue();

            List<IncidentContext> incidents = response.getResponse().getIncidentContextsList();
            assertThat(incidents).hasSize(1);
            assertThat(incidents.get(0).getFileURI()).contains("LegacyService.java");
            assertThat(incidents.get(0).getVariables().getFieldsMap().get("name").getStringValue())
                    .isEqualTo("servlet");
        }

        @Test
        @Order(9)
        void shouldReturnNoMatchForUnknownPattern() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "com.nonexistent.DoesNotExist"
                              location: "IMPORT"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getResponse().getMatched()).isFalse();
            assertThat(response.getResponse().getIncidentContextsList()).isEmpty();
        }

        @Test
        @Order(10)
        void shouldReturnIncidentVariables() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.Singleton"
                              location: "ANNOTATION"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);
            assertThat(response.getResponse().getMatched()).isTrue();

            IncidentContext incident = response.getResponse().getIncidentContexts(0);
            var vars = incident.getVariables().getFieldsMap();

            assertThat(vars).containsKey("kind");
            assertThat(vars).containsKey("name");
            assertThat(vars).containsKey("file");
            assertThat(vars).containsKey("package");

            assertThat(vars.get("name").getStringValue()).isEqualTo("Singleton");
            assertThat(vars.get("package").getStringValue()).isEqualTo("com.example.legacy");
            assertThat(vars.get("file").getStringValue()).startsWith("file://");
        }

        @Test
        @Order(11)
        void shouldReturnCodeLocation() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.Stateless"
                              location: "ANNOTATION"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);
            IncidentContext incident = response.getResponse().getIncidentContexts(0);

            assertThat(incident.hasCodeLocation()).isTrue();
            Location codeLoc = incident.getCodeLocation();
            assertThat(codeLoc.getStartPosition().getLine()).isGreaterThanOrEqualTo(0);
            assertThat(codeLoc.getEndPosition().getLine())
                    .isGreaterThanOrEqualTo(codeLoc.getStartPosition().getLine());
        }

        @Test
        @Order(12)
        void shouldGetCodeSnippet() {
            EvaluateRequest evalRequest = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.Stateless"
                              location: "ANNOTATION"
                            """)
                    .build();

            EvaluateResponse evalResponse = providerStub.evaluate(evalRequest);
            IncidentContext incident = evalResponse.getResponse().getIncidentContexts(0);

            GetCodeSnipRequest snipRequest = GetCodeSnipRequest.newBuilder()
                    .setUri(incident.getFileURI())
                    .setCodeLocation(incident.getCodeLocation())
                    .build();

            GetCodeSnipResponse snipResponse = codeSnipStub.getCodeSnip(snipRequest);

            assertThat(snipResponse.getSnip()).isNotEmpty();
            assertThat(snipResponse.getSnip()).contains("@Stateless");
            assertThat(snipResponse.getSnip()).contains("OldServlet");
        }

        @Test
        @Order(13)
        void shouldGetDependencies() {
            Config config = Config.newBuilder()
                    .setLocation(tempDir.toString())
                    .setAnalysisMode("source-only")
                    .build();

            InitResponse initResponse = providerStub.init(config);
            assertThat(initResponse.getSuccessful()).isTrue();

            ServiceRequest request = ServiceRequest.newBuilder()
                    .setId(initResponse.getId())
                    .build();

            DependencyResponse response = providerStub.getDependencies(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getFileDepList()).isNotEmpty();

            FileDep fileDep = response.getFileDep(0);
            assertThat(fileDep.getFileURI()).contains("pom.xml");

            List<String> depNames = fileDep.getList().getDepsList().stream()
                    .map(Dependency::getName)
                    .toList();
            assertThat(depNames).contains("javax.servlet.javax.servlet-api");
            assertThat(depNames).contains("javax.ejb.javax.ejb-api");
        }

        @Test
        @Order(14)
        void shouldGetDependenciesDAG() {
            Config config = Config.newBuilder()
                    .setLocation(tempDir.toString())
                    .setAnalysisMode("source-only")
                    .build();

            InitResponse initResponse = providerStub.init(config);
            assertThat(initResponse.getSuccessful()).isTrue();

            ServiceRequest request = ServiceRequest.newBuilder()
                    .setId(initResponse.getId())
                    .build();

            DependencyDAGResponse response = providerStub.getDependenciesDAG(request);

            assertThat(response.getSuccessful()).isTrue();
            assertThat(response.getFileDagDepCount()).isEqualTo(1);
            assertThat(response.getFileDagDep(0).getFileURI()).contains("pom.xml");

            var topLevel = response.getFileDagDep(0).getListList();
            assertThat(topLevel).isNotEmpty();

            var topLevelNames = topLevel.stream()
                    .map(item -> item.getKey().getName())
                    .toList();
            assertThat(topLevelNames).contains("javax.servlet.javax.servlet-api");
            assertThat(topLevelNames).contains("javax.ejb.javax.ejb-api");

            for (var item : topLevel) {
                assertThat(item.getKey().getIndirect()).isFalse();
            }
        }

        @Test
        @Order(15)
        void shouldFailEvaluateForUnknownWorkspace() {
            EvaluateRequest request = EvaluateRequest.newBuilder()
                    .setId(99999)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "anything"
                              location: "IMPORT"
                            """)
                    .build();

            EvaluateResponse response = providerStub.evaluate(request);

            assertThat(response.getSuccessful()).isFalse();
            assertThat(response.getError()).contains("unknown workspace id");
        }

        @Test
        @Order(16)
        void shouldStopWorkspace() {
            ServiceRequest request = ServiceRequest.newBuilder()
                    .setId(workspaceId)
                    .build();

            Empty response = providerStub.stop(request);
            assertThat(response).isNotNull();

            EvaluateRequest evalRequest = EvaluateRequest.newBuilder()
                    .setId(workspaceId)
                    .setCap("referenced")
                    .setConditionInfo("""
                            referenced:
                              pattern: "javax.ejb.*"
                              location: "IMPORT"
                            """)
                    .build();

            EvaluateResponse evalResponse = providerStub.evaluate(evalRequest);
            assertThat(evalResponse.getSuccessful()).isFalse();
            assertThat(evalResponse.getError()).contains("unknown workspace id");
        }
    }
}

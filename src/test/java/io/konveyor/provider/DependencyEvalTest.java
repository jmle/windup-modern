package io.konveyor.provider;

import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.IncidentContext;
import io.konveyor.provider.grpc.ProviderEvaluateResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyEvalTest {

    @TempDir
    static Path tempDir;

    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), "package com; public class App {}");

        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>5.3.20</version>
                        </dependency>
                        <dependency>
                            <groupId>javax.servlet</groupId>
                            <artifactId>javax.servlet-api</artifactId>
                            <version>4.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Config config = Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build();
        ctx = new WorkspaceContext(1, tempDir.toString(), "source-only", config, 10);
        ctx.index();
    }

    @Test
    void matchesExactName() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isEqualTo(1);
        IncidentContext incident = resp.getIncidentContexts(0);
        assertThat(incident.getVariables().getFieldsMap().get("name").getStringValue())
                .isEqualTo("org.springframework.spring-core");
        assertThat(incident.getVariables().getFieldsMap().get("version").getStringValue())
                .isEqualTo("5.3.20");
    }

    @Test
    void noMatchReturnsNotMatched() {
        String conditionInfo = """
                dependency:
                  name: com.nonexistent.nothing
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void matchesNameRegex() {
        String conditionInfo = """
                dependency:
                  name_regex: javax\\..*
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isGreaterThanOrEqualTo(1);
        assertThat(resp.getIncidentContextsList().stream()
                .map(i -> i.getVariables().getFieldsMap().get("name").getStringValue())
                .toList())
                .contains("javax.servlet.javax.servlet-api");
    }

    @Test
    void matchesWithLowerbound() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  lowerbound: 5.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isTrue();
    }

    @Test
    void rejectsWhenBelowLowerbound() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  lowerbound: 6.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void matchesWithUpperbound() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  upperbound: 6.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isTrue();
    }

    @Test
    void rejectsWhenAboveUpperbound() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  upperbound: 4.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void matchesWithBothBounds() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  lowerbound: 5.0.0
                  upperbound: 6.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isTrue();
    }

    @Test
    void rejectsWhenOutsideBothBounds() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                  lowerbound: 6.0.0
                  upperbound: 7.0.0
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void incidentPointsToBuildFile() {
        String conditionInfo = """
                dependency:
                  name: org.springframework.spring-core
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", conditionInfo);
        assertThat(resp.getMatched()).isTrue();

        String fileUri = resp.getIncidentContexts(0).getFileURI();
        assertThat(fileUri).endsWith("pom.xml");
    }
}

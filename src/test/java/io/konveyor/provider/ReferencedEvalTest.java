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

class ReferencedEvalTest {

    @TempDir
    static Path tempDir;

    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        Path srcDir = tempDir.resolve("com/example");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("Customer.java"), """
                package com.example;
                public class Customer {
                    private String name;
                }
                """);

        Files.writeString(srcDir.resolve("Controller.java"), """
                package com.example;

                import com.example.Customer;
                import org.springframework.web.bind.annotation.GetMapping;

                public class Controller {

                    @GetMapping
                    public Customer getById(Long id) {
                        return null;
                    }

                    public Customer findAll() {
                        return null;
                    }
                }
                """);

        Config config = Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build();
        ctx = new WorkspaceContext(1, tempDir.toString(), "source-only", config, 10);
        ctx.index();
    }

    @Test
    void methodWithReturnTypePattern() {
        String conditionInfo = """
                referenced:
                  pattern: "* com.example.Customer"
                  location: method
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isEqualTo(2);
        assertThat(resp.getIncidentContextsList().stream()
                .map(i -> i.getVariables().getFieldsMap().get("name").getStringValue())
                .toList())
                .containsExactlyInAnyOrder("getById", "findAll");
    }

    @Test
    void methodWithReturnTypeAndAnnotation() {
        String conditionInfo = """
                referenced:
                  pattern: "* com.example.Customer"
                  location: method
                  annotated:
                    pattern: org.springframework.web.bind.annotation.GetMapping
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isEqualTo(1);
        IncidentContext incident = resp.getIncidentContexts(0);
        assertThat(incident.getVariables().getFieldsMap().get("name").getStringValue())
                .isEqualTo("getById");
    }

    @Test
    void methodWithReturnTypeAndWrongAnnotation() {
        String conditionInfo = """
                referenced:
                  pattern: "* com.example.Customer"
                  location: method
                  annotated:
                    pattern: javax.ejb.Singleton
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", conditionInfo);

        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void simpleMethodPattern() {
        String conditionInfo = """
                referenced:
                  pattern: com.example.Controller.getById
                  location: method
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isEqualTo(1);
    }

    @Test
    void simpleMethodWithAnnotation() {
        String conditionInfo = """
                referenced:
                  pattern: com.example.Controller.*
                  location: method
                  annotated:
                    pattern: org.springframework.web.bind.annotation.GetMapping
                """;

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", conditionInfo);

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isGreaterThanOrEqualTo(1);
        assertThat(resp.getIncidentContextsList().stream()
                .map(i -> i.getVariables().getFieldsMap().get("name").getStringValue())
                .distinct().toList())
                .containsExactly("getById");
    }
}

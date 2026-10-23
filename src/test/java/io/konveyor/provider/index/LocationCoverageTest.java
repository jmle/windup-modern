package io.konveyor.provider.index;

import io.konveyor.provider.WorkspaceContext;
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

/**
 * Synthetic location/condition coverage — a <b>provider-independent</b> oracle for the
 * {@code referenced} capability. Unlike {@link E2EParityTest} (which replicates the Go
 * provider's e2e suite and is gated on external example projects), this test:
 *
 * <ul>
 *   <li>is fully self-contained (fixtures written to a temp dir, no external checkout),</li>
 *   <li>asserts matches that are known <em>by construction</em> from the fixtures — not by
 *       comparison to the Go provider,</li>
 *   <li>drives the real {@code referenced} evaluate path (YAML condition → incidents), so it
 *       exercises condition parsing, location mapping, dedup and incident construction, not just
 *       {@link SymbolIndex} lookups.</li>
 * </ul>
 *
 * Incident {@code lineNumber} is 1-based (the provider emits {@code symbol.line() + 1}).
 *
 * <p>Scaffold: extend with {@code annotated} elements, {@code filepaths} filtering,
 * dependency-vs-application incident flags, and glob variants — one case per row of
 * {@code LOCATION_DIFFERENCES.md}.</p>
 */
class LocationCoverageTest {

    @TempDir
    static Path tempDir;

    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        Path src = tempDir.resolve("com/example/apps");
        Files.createDirectories(src);

        // Bean.java — @Singleton annotation (L7), import (L3), class decl (L8),
        // implements SessionBean (L8)
        Files.writeString(src.resolve("Bean.java"), """
                package com.example.apps;

                import javax.ejb.Singleton;
                import javax.ejb.SessionBean;
                import org.springframework.stereotype.*;

                @Singleton
                public class Bean implements SessionBean {

                    private String name;

                    public Bean() {
                        this.name = "default";
                    }

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """);

        // Priority.java — enum declaration (L3) + constants
        Files.writeString(src.resolve("Priority.java"), """
                package com.example.apps;

                public enum Priority {
                    HIGH,
                    MEDIUM,
                    LOW
                }
                """);

        // Service.java — field of type Bean (L9), method call (L17), return type Bean (L20),
        // constructor call new Bean() (L21), method decl doStuff (L16)
        Files.writeString(src.resolve("Service.java"), """
                package com.example.apps;

                import com.example.apps.Bean;
                import org.springframework.beans.factory.annotation.Autowired;

                public class Service {

                    @Autowired
                    private Bean myBean;
                    private Priority priority = Priority.HIGH;

                    public void doStuff() {
                        String n = myBean.getName();
                    }

                    public Bean createBean() {
                        return new Bean();
                    }
                }
                """);

        // Child.java — extends Bean (INHERITANCE)
        Files.writeString(src.resolve("Child.java"), """
                package com.example.apps;

                public class Child extends Bean {
                }
                """);

        Config config = Config.newBuilder()
                .setLocation(tempDir.toString())
                .setAnalysisMode("source-only")
                .build();
        ctx = new WorkspaceContext(0, tempDir.toString(), "source-only", config, 10);
        ctx.index();
    }

    // ─── location matrix ───

    @Test
    void importLocation() {
        assertMatchedAt(eval("javax.ejb.Singleton", "IMPORT"), "Bean.java", 3);
    }

    @Test
    void annotationLocation() {
        assertMatchedAt(eval("javax.ejb.Singleton", "ANNOTATION"), "Bean.java", 7);
    }

    @Test
    void annotationFromStarImport() {
        assertMatchedAt(eval("org.springframework.beans.factory.annotation.Autowired", "ANNOTATION"),
                "Service.java", 8);
    }

    @Test
    void typeLocation() {
        assertMatched(eval("com.example.apps.Bean", "TYPE"), "Bean.java");
    }

    @Test
    void classLocation() {
        assertMatchedAt(eval("com.example.apps.Bean", "CLASS"), "Bean.java", 8);
    }

    @Test
    void inheritanceLocation() {
        // Child extends Bean
        assertMatchedAt(eval("com.example.apps.Bean", "INHERITANCE"), "Child.java", 3);
    }

    @Test
    void implementsTypeLocation() {
        // Bean implements SessionBean
        assertMatchedAt(eval("javax.ejb.SessionBean", "IMPLEMENTS_TYPE"), "Bean.java", 8);
    }

    @Test
    void fieldLocation() {
        assertMatchedAt(eval("com.example.apps.Bean", "FIELD"), "Service.java", 9);
    }

    @Test
    void methodLocation() {
        assertMatched(eval("com.example.apps.Service.doStuff", "METHOD"), "Service.java");
    }

    @Test
    void methodCallLocation() {
        // receiver resolved through field type — incident is in the calling file
        assertMatched(eval("com.example.apps.Bean.getName", "METHOD_CALL"), "Service.java");
    }

    @Test
    void constructorCallLocation() {
        // new Bean() is on line 17
        assertMatchedAt(eval("com.example.apps.Bean", "CONSTRUCTOR_CALL"), "Service.java", 17);
    }

    @Test
    void returnTypeLocation() {
        // createBean() declaration is on line 16
        assertMatchedAt(eval("com.example.apps.Bean", "RETURN_TYPE"), "Service.java", 16);
    }

    @Test
    void enumLocation() {
        assertMatched(eval("com.example.apps.Priority", "ENUM"), "Priority.java");
    }

    @Test
    void packageLocation() {
        // PACKAGE is derived from the IMPORT bucket's package portion
        assertThat(eval("javax.ejb", "PACKAGE").getMatched()).isTrue();
    }

    @Test
    void variableDeclarationLocation() {
        // Without binding resolution, "String" resolves via package fallback to
        // "com.example.apps.String" (documented quirk — see LOCATION_DIFFERENCES.md)
        assertThat(eval("com.example.apps.String", "VARIABLE_DECLARATION").getMatched()).isTrue();
    }

    @Test
    void defaultLocation() {
        // KNOWN DIVERGENCE: our default maps to TYPE only, whereas Go's default also searches
        // METHOD + CONSTRUCTOR. Asserting current behavior; see LOCATION_DIFFERENCES.md.
        assertThat(eval("com.example.apps.Bean", "").getMatched()).isTrue();
    }

    @Test
    void noMatchReturnsUnmatched() {
        ProviderEvaluateResponse resp = eval("com.does.not.Exist", "TYPE");
        assertThat(resp.getMatched()).isFalse();
        assertThat(resp.getIncidentContextsList()).isEmpty();
    }

    // ─── helpers ───

    private static ProviderEvaluateResponse eval(String pattern, String location) {
        return ctx.evaluate("referenced", referenced(pattern, location));
    }

    private static String referenced(String pattern, String location) {
        StringBuilder sb = new StringBuilder("referenced:\n  pattern: '");
        sb.append(pattern).append("'\n");
        if (location != null && !location.isEmpty()) {
            sb.append("  location: ").append(location).append("\n");
        }
        return sb.toString();
    }

    private static void assertMatched(ProviderEvaluateResponse resp, String fileSubstring) {
        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsList()).isNotEmpty();
        assertThat(resp.getIncidentContextsList())
                .anyMatch(i -> i.getFileURI().contains(fileSubstring));
    }

    private static void assertMatchedAt(ProviderEvaluateResponse resp, String fileSubstring, int line) {
        assertMatched(resp, fileSubstring);
        assertThat(resp.getIncidentContextsList())
                .as("expected an incident in %s at line %d", fileSubstring, line)
                .anyMatch(i -> i.getFileURI().contains(fileSubstring) && i.getLineNumber() == line);
    }
}

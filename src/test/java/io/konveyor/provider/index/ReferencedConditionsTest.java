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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * By-construction oracle for the full {@code referenced} condition surface (MTA-8154) — beyond plain
 * location matching: the {@code annotated} sub-condition (annotation presence + element values),
 * {@code filepaths} filtering, glob/alternation patterns, and the method-with-return-type form. Fixtures
 * have known answers, so this proves the provider can do what migration rules require, independently of
 * the Go provider.
 */
class ReferencedConditionsTest {

    @TempDir
    static Path tempDir;
    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        Path model = tempDir.resolve("com/example/model");
        Files.createDirectories(model);
        Files.writeString(model.resolve("Order.java"), """
                package com.example.model;

                import javax.persistence.Entity;
                import javax.persistence.Table;
                import javax.persistence.Column;
                import javax.persistence.NamedQuery;

                @Entity
                @Table(name = "orders")
                @NamedQuery(name = "Order.findAll", query = "select o from Order o")
                public class Order {
                    @Column(nullable = false)
                    private String code;
                }
                """);
        Files.writeString(model.resolve("Plain.java"), """
                package com.example.model;
                public class Plain { private String x; }
                """);

        Path svc = tempDir.resolve("com/example/svc");
        Files.createDirectories(svc);
        Files.writeString(svc.resolve("OrderService.java"), """
                package com.example.svc;

                import javax.ejb.Stateless;
                import com.example.model.Order;

                @Stateless
                public class OrderService {
                    public Order find() { return new Order(); }
                    public java.util.List<Order> list() { return null; }
                }
                """);

        Config config = Config.newBuilder().setLocation(tempDir.toString())
                .setAnalysisMode("source-only").build();
        ctx = new WorkspaceContext(0, tempDir.toString(), "source-only", config, 10);
        ctx.index();
    }

    // ── annotated: presence ──────────────────────────────────────────────

    @Test
    void annotatedFindsAllTypesCarryingAnAnnotation() {
        // pattern "*" over CLASS, filtered to those annotated @Entity -> only Order
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("*", "CLASS") + annotatedBlock("javax.persistence.Entity", null));
        assertThat(r.getMatched()).isTrue();
        assertThat(files(r)).containsExactly("Order.java");
    }

    @Test
    void annotatedExcludesTypesWithoutThatAnnotation() {
        // Order is not @Stateless, so filtering Order by @Stateless yields nothing
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("com.example.model.Order", "CLASS")
                        + annotatedBlock("javax.ejb.Stateless", null));
        assertThat(r.getMatched()).isFalse();
    }

    // ── annotated: element values (regex-matched) ────────────────────────

    @Test
    void annotatedMatchesOnElementValue() {
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("com.example.model.Order", "CLASS")
                        + annotatedBlock("javax.persistence.NamedQuery",
                        new String[][]{{"name", "Order.findAll"}}));
        assertThat(r.getMatched()).isTrue();
        assertThat(files(r)).contains("Order.java");
    }

    @Test
    void annotatedElementValueIsRegex() {
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("com.example.model.Order", "CLASS")
                        + annotatedBlock("javax.persistence.NamedQuery",
                        new String[][]{{"name", ".*findAll"}}));
        assertThat(r.getMatched()).isTrue();
    }

    @Test
    void annotatedRejectsWrongElementValue() {
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("com.example.model.Order", "CLASS")
                        + annotatedBlock("javax.persistence.NamedQuery",
                        new String[][]{{"name", "Nope"}}));
        assertThat(r.getMatched()).isFalse();
    }

    // ── filepaths filtering ──────────────────────────────────────────────

    @Test
    void filepathsRestrictsToListedFile() {
        Path orderFile = tempDir.resolve("com/example/model/Order.java");
        ProviderEvaluateResponse match = ctx.evaluate("referenced",
                referenced("javax.persistence.Entity", "ANNOTATION") + filepathsBlock(orderFile));
        assertThat(match.getMatched()).isTrue();
        assertThat(files(match)).containsExactly("Order.java");

        Path otherFile = tempDir.resolve("com/example/svc/OrderService.java");
        ProviderEvaluateResponse none = ctx.evaluate("referenced",
                referenced("javax.persistence.Entity", "ANNOTATION") + filepathsBlock(otherFile));
        assertThat(none.getMatched()).isFalse();
    }

    // ── glob / alternation ───────────────────────────────────────────────

    @Test
    void globWildcardMatchesPackage() {
        assertThat(ctx.evaluate("referenced", referenced("javax.ejb.*", "ANNOTATION")).getMatched()).isTrue();
        assertThat(ctx.evaluate("referenced", referenced("javax.persistence.*", "ANNOTATION")).getMatched()).isTrue();
        assertThat(ctx.evaluate("referenced", referenced("javax.jms.*", "ANNOTATION")).getMatched()).isFalse();
    }

    @Test
    void globAlternationMatchesEitherBranch() {
        assertThat(ctx.evaluate("referenced",
                referenced("javax.(ejb|persistence).Entity", "ANNOTATION")).getMatched()).isTrue();
        assertThat(ctx.evaluate("referenced",
                referenced("javax.(ejb|persistence).Stateless", "ANNOTATION")).getMatched()).isTrue();
    }

    @Test
    void globSuffixMatchesTypeName() {
        assertThat(ctx.evaluate("referenced", referenced("*Service", "CLASS")).getMatched()).isTrue();
    }

    // ── method with return type ──────────────────────────────────────────

    @Test
    void methodReturnTypePatternMatches() {
        // "<name> <returnType>": any method returning com.example.model.Order
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                referenced("* com.example.model.Order", "METHOD"));
        assertThat(r.getMatched()).isTrue();
        assertThat(files(r)).contains("OrderService.java");  // find() returns Order
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String referenced(String pattern, String location) {
        return "referenced:\n  pattern: '" + pattern + "'\n  location: " + location + "\n";
    }

    private static String annotatedBlock(String pattern, String[][] elements) {
        StringBuilder sb = new StringBuilder("  annotated:\n    pattern: " + pattern + "\n");
        if (elements != null) {
            sb.append("    elements:\n");
            for (String[] e : elements) {
                sb.append("      - name: ").append(e[0]).append("\n");
                sb.append("        value: '").append(e[1]).append("'\n");
            }
        }
        return sb.toString();
    }

    private static String filepathsBlock(Path file) {
        return "  filepaths:\n    - " + file.toString() + "\n";
    }

    private static List<String> files(ProviderEvaluateResponse r) {
        return r.getIncidentContextsList().stream()
                .map(IncidentContext::getFileURI)
                .map(u -> u.substring(u.lastIndexOf('/') + 1))
                .distinct().sorted().toList();
    }
}

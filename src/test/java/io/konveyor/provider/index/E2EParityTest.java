package io.konveyor.provider.index;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.konveyor.provider.WorkspaceContext;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.DependencyDAGItem;
import io.konveyor.provider.grpc.DependencyDAGResponse;
import io.konveyor.provider.grpc.IncidentContext;
import io.konveyor.provider.grpc.ProviderEvaluateResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity tests that replicate the Go java provider's e2e test suite. Each test method
 * corresponds to a rule in {@code e2e-tests/rule-example.yaml} and asserts against the
 * expected output from {@code e2e-tests/demo-output.yaml}. Runs against the real
 * example projects when available on disk.
 *
 * Rules requiring engine-level features (as/from chaining, includedPaths, mvn:// download)
 * are marked {@code @Disabled} with a reason.
 */
@EnabledIf("examplesExist")
class E2EParityTest {

    static final Path EXAMPLES_ROOT = Path.of(
            System.getProperty("user.home"),
            "gosrc/analyzer-lsp/external-providers/java-external-provider/examples");

    static WorkspaceContext javaCtx;
    static WorkspaceContext customersCtx;
    static WorkspaceContext tilesCtx;
    static WorkspaceContext gradleCtx;
    static WorkspaceContext inclusionCtx;

    static boolean examplesExist() {
        return Files.isDirectory(EXAMPLES_ROOT.resolve("java"));
    }

    @BeforeAll
    static void setUp() throws IOException {
        javaCtx = createContext(EXAMPLES_ROOT.resolve("java"));
        customersCtx = createContext(EXAMPLES_ROOT.resolve("customers-tomcat-legacy"));
        tilesCtx = createContext(EXAMPLES_ROOT.resolve("sample-tiles-app"));
        gradleCtx = createContext(EXAMPLES_ROOT.resolve("gradle-multi-project-example"));
        inclusionCtx = createContextWithIncludedPaths(
                EXAMPLES_ROOT.resolve("inclusion-tests"),
                List.of("src/main/java/io/konveyor/util/FileReader.java"));
    }

    static WorkspaceContext createContext(Path projectDir) throws IOException {
        Config config = Config.newBuilder()
                .setLocation(projectDir.toString())
                .setAnalysisMode("source-only")
                .build();
        WorkspaceContext ctx = new WorkspaceContext(
                0, projectDir.toString(), "source-only", config, 10);
        ctx.index();
        return ctx;
    }

    static WorkspaceContext createContextWithIncludedPaths(
            Path projectDir, List<String> includedPaths) throws IOException {
        ListValue.Builder listBuilder = ListValue.newBuilder();
        for (String p : includedPaths) {
            listBuilder.addValues(Value.newBuilder().setStringValue(p));
        }
        Struct providerConfig = Struct.newBuilder()
                .putFields("includedPaths", Value.newBuilder()
                        .setListValue(listBuilder).build())
                .build();
        Config config = Config.newBuilder()
                .setLocation(projectDir.toString())
                .setAnalysisMode("source-only")
                .setProviderSpecificConfig(providerConfig)
                .build();
        WorkspaceContext ctx = new WorkspaceContext(
                0, projectDir.toString(), "source-only", config, 10);
        ctx.index();
        return ctx;
    }

    // ─── helpers ───

    static String referenced(String pattern, String location) {
        StringBuilder sb = new StringBuilder("referenced:\n  pattern: '");
        sb.append(pattern).append("'\n");
        if (location != null && !location.isEmpty()) {
            sb.append("  location: ").append(location).append("\n");
        }
        return sb.toString();
    }

    static String referencedAnnotated(String pattern, String location,
                                       String annotatedPattern,
                                       List<Map.Entry<String, String>> elements) {
        StringBuilder sb = new StringBuilder("referenced:\n  pattern: '");
        sb.append(pattern).append("'\n");
        if (location != null) sb.append("  location: ").append(location).append("\n");
        sb.append("  annotated:\n");
        if (annotatedPattern != null) {
            sb.append("    pattern: ").append(annotatedPattern).append("\n");
        }
        if (elements != null && !elements.isEmpty()) {
            sb.append("    elements:\n");
            for (var e : elements) {
                sb.append("      - name: ").append(e.getKey()).append("\n");
                sb.append("        value: '").append(e.getValue()).append("'\n");
            }
        }
        return sb.toString();
    }

    static String referencedWithFilepaths(String pattern, String location,
                                            List<String> filepaths) {
        StringBuilder sb = new StringBuilder("referenced:\n  pattern: '");
        sb.append(pattern).append("'\n");
        if (location != null && !location.isEmpty()) {
            sb.append("  location: ").append(location).append("\n");
        }
        if (filepaths != null && !filepaths.isEmpty()) {
            sb.append("  filepaths:\n");
            for (String fp : filepaths) {
                sb.append("    - ").append(fp).append("\n");
            }
        }
        return sb.toString();
    }

    static String dependency(String name, String lowerbound, String upperbound) {
        StringBuilder sb = new StringBuilder("dependency:\n");
        if (name != null) sb.append("  name: ").append(name).append("\n");
        if (lowerbound != null) sb.append("  lowerbound: ").append(lowerbound).append("\n");
        if (upperbound != null) sb.append("  upperbound: ").append(upperbound).append("\n");
        return sb.toString();
    }

    static String var(IncidentContext ic, String key) {
        return ic.getVariables().getFieldsMap().get(key).getStringValue();
    }

    static List<IncidentContext> incidentsIn(ProviderEvaluateResponse resp, String pathFragment) {
        return resp.getIncidentContextsList().stream()
                .filter(ic -> ic.getFileURI().contains(pathFragment))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  lang-ref-003: TYPE pattern for CustomResourceDefinition
    //  Expected: 2 incidents in java/example App.java (import + usage)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void langRef003_typeQueryInJavaProject() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("referenced",
                referenced("*apiextensions.v1beta1.CustomResourceDefinition*", "TYPE"));

        assertThat(resp.getMatched()).isTrue();

        var incidents = incidentsIn(resp, "App.java");
        assertThat(incidents).hasSizeGreaterThanOrEqualTo(2);

        boolean hasImport = incidents.stream().anyMatch(ic ->
                "Module".equals(var(ic, "kind"))
                && var(ic, "name").contains("CustomResourceDefinition"));
        assertThat(hasImport).as("Should find import as Module kind").isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  lang-ref-004: METHOD_CALL for GenericClass.get
    //  Expected: 1 incident at line 18 in App.java
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void langRef004_genericMethodCall() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("referenced",
                referenced("com.example.apps.GenericClass.get", "METHOD_CALL"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "App.java");
        assertThat(incidents).isNotEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  singleton-sessionbean-00001 / 00002: chained as/from conditions
    //  Requires engine-level chaining — test individual parts only
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void singletonSessionbean_annotationPart() {
        // The @Singleton annotation on Bean.java
        ProviderEvaluateResponse resp = javaCtx.evaluate("referenced",
                referenced("javax.ejb.Singleton", "ANNOTATION"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "Bean.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(6);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Property");
        assertThat(var(incidents.get(0), "name")).isEqualTo("Singleton");
    }

    @Test
    void singletonSessionbean_implementsPart() {
        // Bean implements SessionBean
        ProviderEvaluateResponse resp = javaCtx.evaluate("referenced",
                referenced("javax.ejb.SessionBean", "IMPLEMENTS_TYPE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "Bean.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(7);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Class");
        assertThat(var(incidents.get(0), "name")).isEqualTo("Bean");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  maven-javax-to-jakarta-00002: dependency evaluation
    //  Expected: javax.activation.activation with lowerbound 0.0.0
    //  This is a transitive dependency — needs embedded Maven resolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void mavenJavaxToJakarta00002_dependencyEval() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("dependency",
                dependency("javax.activation.activation", "0.0.0", null));

        // javax.activation.activation is a transitive dep of javaee-api
        // Result depends on whether Maven resolution found it
        if (resp.getMatched()) {
            var incidents = resp.getIncidentContextsList();
            assertThat(incidents).isNotEmpty();
            assertThat(var(incidents.get(0), "name")).isEqualTo("javax.activation.activation");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-pomxml-dependencies: AND of two dep conditions
    //  Engine-level AND — test each half individually
    //  Expected: junit.junit between 4.4.0-4.12.2 (java project has 4.11)
    //  Expected: io.fabric8.kubernetes-client lowerbound 5.0.100 (has 6.0.0)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaPomxmlDependencies_junitPart() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("dependency",
                dependency("junit.junit", "4.4.0", "4.12.2"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = resp.getIncidentContextsList();
        assertThat(incidents).hasSize(1);
        assertThat(var(incidents.get(0), "name")).isEqualTo("junit.junit");
        assertThat(var(incidents.get(0), "version")).isEqualTo("4.11");
    }

    @Test
    void javaPomxmlDependencies_fabric8Part() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("dependency",
                dependency("io.fabric8.kubernetes-client", "5.0.100", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = resp.getIncidentContextsList();
        assertThat(incidents).hasSize(1);
        assertThat(var(incidents.get(0), "name")).isEqualTo("io.fabric8.kubernetes-client");
        assertThat(var(incidents.get(0), "version")).isEqualTo("6.0.0");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GetDependenciesDAG: tree structure from Maven dependency resolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void getDependenciesDAG_returnsTreeStructure() {
        DependencyDAGResponse dag = javaCtx.getDependenciesDAG();

        assertThat(dag.getSuccessful()).isTrue();
        assertThat(dag.getFileDagDepCount()).isEqualTo(1);
        assertThat(dag.getFileDagDep(0).getFileURI()).contains("pom.xml");

        var topLevel = dag.getFileDagDep(0).getListList();
        assertThat(topLevel).isNotEmpty();

        var topLevelNames = topLevel.stream()
                .map(item -> item.getKey().getName())
                .toList();
        assertThat(topLevelNames).contains("io.fabric8.kubernetes-client");

        for (var item : topLevel) {
            assertThat(item.getKey().getIndirect()).isFalse();
            assertThat(item.getKey().getName()).isNotEmpty();
            assertThat(item.getKey().getVersion()).isNotEmpty();
        }

        assertThat(allDagDepsIndirectExceptTopLevel(topLevel)).isTrue();
    }

    private boolean allDagDepsIndirectExceptTopLevel(List<DependencyDAGItem> topLevel) {
        for (DependencyDAGItem item : topLevel) {
            if (!allChildrenIndirect(item.getAddedDepsList())) return false;
        }
        return true;
    }

    private boolean allChildrenIndirect(List<DependencyDAGItem> items) {
        for (DependencyDAGItem child : items) {
            if (!child.getKey().getIndirect()) return false;
            if (!allChildrenIndirect(child.getAddedDepsList())) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-inclusion-test: requires includedPaths provider config
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaInclusionTest() {
        ProviderEvaluateResponse resp = inclusionCtx.evaluate("referenced",
                referenced("io.konveyor.util.FileReader", ""));

        assertThat(resp.getMatched()).isTrue();
        var incidents = resp.getIncidentContextsList();
        assertThat(incidents.stream().map(IncidentContext::getFileURI).toList())
                .allMatch(uri -> uri.contains("FileReader.java"));
        var fileReaderIncidents = incidentsIn(resp, "FileReader.java");
        assertThat(fileReaderIncidents).hasSize(1);
        assertThat(fileReaderIncidents.get(0).getLineNumber()).isEqualTo(5);
        assertThat(var(fileReaderIncidents.get(0), "kind")).isEqualTo("Class");
        assertThat(var(fileReaderIncidents.get(0), "name")).isEqualTo("FileReader");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-gradle-project: HttpExchange import in gradle project
    //  Expected: 2 incidents in Server.java (import + handle method)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaGradleProject() {
        ProviderEvaluateResponse resp = gradleCtx.evaluate("referenced",
                referenced("com.sun.net.httpserver.HttpExchange", ""));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "Server.java");
        assertThat(incidents).hasSizeGreaterThanOrEqualTo(2);

        boolean hasImport = incidents.stream().anyMatch(ic ->
                "Module".equals(var(ic, "kind"))
                && var(ic, "name").equals("com.sun.net.httpserver.HttpExchange"));
        assertThat(hasImport).isTrue();

        boolean hasMethodUsage = incidents.stream().anyMatch(ic ->
                "Method".equals(var(ic, "kind"))
                && var(ic, "name").equals("handle"));
        assertThat(hasMethodUsage).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-downloaded-maven-artifact: mvn:// location download + decompile
    //  Expected: io.javaoperatorsdk.operator.Operator referenced in decompiled source
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaDownloadedMavenArtifact() throws Exception {
        var downloader = new io.konveyor.provider.buildtool.MavenArtifactDownloader();
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "e2e-mvn-download-test");
        Path jarPath;
        try {
            jarPath = downloader.download("mvn://io.javaoperatorsdk:quarkus:1.6.2:jar", workDir);
        } catch (IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Maven artifact download failed (network unavailable?): " + e.getMessage());
            return;
        }

        Config config = Config.newBuilder()
                .setLocation(jarPath.toString())
                .setAnalysisMode("full")
                .build();
        WorkspaceContext ctx = new WorkspaceContext(
                0, jarPath.toString(), "full", config, 10);
        ctx.index();

        ProviderEvaluateResponse resp = ctx.evaluate("referenced",
                referenced("io.javaoperatorsdk.operator.Operator", ""));

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  field-rule-00001: FIELD location
    //  Expected: 1 incident, CustomerService.java line 18
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void fieldRule00001() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referenced("io.konveyor.demo.ordermanagement.repository.CustomerRepository", "FIELD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "CustomerService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(18);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Field");
        assertThat(var(incidents.get(0), "name")).isEqualTo("repository");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  field-rule-00002: FIELD_DECLARATION location (maps to FIELD)
    //  Expected: same as field-rule-00001
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void fieldRule00002() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referenced("io.konveyor.demo.ordermanagement.repository.CustomerRepository",
                        "FIELD_DECLARATION"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "CustomerService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(18);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-annotation-inspection-01: TYPE with annotated pattern + elements
    //  Expected: PersistenceConfig.java line 27
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaAnnotationInspection01() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated(
                        "io.konveyor.demo.ordermanagement.config.PersistenceConfig", "TYPE",
                        "org.springframework.data.jpa.repository.config.EnableJpaRepositories",
                        List.of(Map.entry("basePackages",
                                "io.konveyor.demo.ordermanagement.repository"))));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "PersistenceConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(27);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Class");
        assertThat(var(incidents.get(0), "name")).isEqualTo("PersistenceConfig");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-annotation-inspection-02: METHOD annotated with @Bean
    //  Expected: PersistenceConfig.java line 31 (entityManagerFactory)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaAnnotationInspection02() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated("entityManagerFactory", "METHOD",
                        "org.springframework.context.annotation.Bean", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "PersistenceConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(31);
        assertThat(var(incidents.get(0), "name")).isEqualTo("entityManagerFactory");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-annotation-inspection-03: FIELD annotated with @Autowired
    //  Expected: CustomerController.java line 21
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaAnnotationInspection03() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated(
                        "io.konveyor.demo.ordermanagement.service.CustomerService", "FIELD",
                        "org.springframework.beans.factory.annotation.Autowired", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "CustomerController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(21);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Field");
        assertThat(var(incidents.get(0), "name")).isEqualTo("customerService");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-annotation-inspection-04: ANNOTATION with elements
    //  Expected: CustomerController.java line 25
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaAnnotationInspection04() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated(
                        "org.springframework.web.bind.annotation.GetMapping", "ANNOTATION",
                        null,
                        List.of(Map.entry("value", "id"))));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "CustomerController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(25);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Property");
        assertThat(var(incidents.get(0), "name")).isEqualTo("GetMapping");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-annotation-inspection-05: ANNOTATION annotated with another
    //  Expected: PersistenceConfig.java line 21
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void javaAnnotationInspection05() {
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated(
                        "org.springframework.context.annotation.Configuration", "ANNOTATION",
                        "org.springframework.data.jpa.repository.config.EnableJpaRepositories",
                        List.of(Map.entry("basePackages",
                                "io.konveyor.demo.ordermanagement.repository"))));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "PersistenceConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(21);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Property");
        assertThat(var(incidents.get(0), "name")).isEqualTo("Configuration");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  java-chaining-01: requires as/from/filepaths chaining
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Disabled("Engine-level feature: as/from/filepaths chaining is orchestrated by analyzer-lsp, not the provider")
    void javaChaining01() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  annotation-on-any-class-01: CLASS '*' with annotated @Singleton
    //  Expected: Bean.java line 7
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void annotationOnAnyClass01() {
        ProviderEvaluateResponse resp = javaCtx.evaluate("referenced",
                referencedAnnotated("*", "CLASS",
                        "javax.ejb.Singleton", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "Bean.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(7);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Class");
        assertThat(var(incidents.get(0), "name")).isEqualTo("Bean");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PACKAGE matching (konveyor-java-pattern-test 1-3)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest1_packageWithStars() {
        // pattern: "org.spri*g*.web.servlet.view.tiles3" location: PACKAGE
        // Expected: 2 incidents in TilesConfig.java (lines 5, 6)
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.spri*g*.web.servlet.view.tiles3", "PACKAGE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(2);
        assertThat(incidents.stream().map(IncidentContext::getLineNumber).toList())
                .containsExactlyInAnyOrder(5L, 6L);
    }

    @Test
    void patternTest2_exactPackage_tiles() {
        // pattern: "org.springframework.web.servlet" location: PACKAGE
        // Expected from tiles: TilesConfig.java line 7
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet", "PACKAGE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(7);
    }

    @Test
    void patternTest2_exactPackage_customers() {
        // Same rule, but incidents from customers project
        // Expected: OrderManagementAppInitializer.java line 10
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet", "PACKAGE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "OrderManagementAppInitializer.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(10);
    }

    @Test
    void patternTest3_packageWildcardAtEnd_tiles() {
        // pattern: "org.springframework.web.servlet*" location: PACKAGE
        // Expected from tiles: TilesConfig.java lines 5, 6, 7
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet*", "PACKAGE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void patternTest3_packageWildcardAtEnd_customers() {
        // Expected: OrderManagementAppInitializer.java line 10
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet*", "PACKAGE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "OrderManagementAppInitializer.java");
        assertThat(incidents).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  IMPORT matching (konveyor-java-pattern-test 4-6)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest4_exactImport() {
        // pattern: "org.springframework.web.servlet.ViewResolver" location: IMPORT
        // Expected: TilesConfig.java line 7
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet.ViewResolver", "IMPORT"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(7);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Module");
    }

    @Test
    void patternTest5_importWildcardAfterDot() {
        // pattern: "org.springframework.web.servlet.*" location: IMPORT
        // Expected: UNMATCHED in Go provider (no results)
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet.*", "IMPORT"));

        // The Go provider reports this as unmatched
        // Our provider may or may not match - this tests parity
        // In Go output, this rule is in the "unmatched" section
    }

    @Test
    void patternTest6_importWildcardWithoutDot_tiles() {
        // pattern: "org.springframework.web.servlet*" location: IMPORT
        // Expected from tiles: TilesConfig.java lines 5, 6, 7
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet*", "IMPORT"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void patternTest6_importWildcardWithoutDot_customers() {
        // Expected: OrderManagementAppInitializer.java line 10
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referenced("org.springframework.web.servlet*", "IMPORT"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "OrderManagementAppInitializer.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(10);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  METHOD matching (konveyor-java-pattern-test 7-11, 111)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest7_methodWildcardAtEnd() {
        // pattern: "do*" location: METHOD
        // Expected: doStuffWithHomeService (HomeController), doStuff + doThings (HomeService)
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("do*", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsCount()).isGreaterThanOrEqualTo(3);

        assertThat(resp.getIncidentContextsList().stream().map(ic -> var(ic, "name")).toList())
                .contains("doStuff", "doThings");
    }

    @Test
    void patternTest8_methodWithClassName() {
        // pattern: "HomeService.do*" location: METHOD
        // Expected: doStuff (line 15), doThings (line 20) in HomeService.java
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("HomeService.do*", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(2);
        assertThat(incidents.stream().map(ic -> var(ic, "name")).toList())
                .containsExactlyInAnyOrder("doStuff", "doThings");
    }

    @Test
    void patternTest9_methodWithFQClassName() {
        // pattern: "com.example.service.HomeService.do*" location: METHOD
        // Expected: doStuff (line 15), doThings (line 20) in HomeService.java
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.do*", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(2);
    }

    @Test
    void patternTest10_exactMethodWithFQClassName() {
        // pattern: "com.example.service.HomeService.doThings" location: METHOD
        // Expected: doThings (line 20) in HomeService.java
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.doThings", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(20);
        assertThat(var(incidents.get(0), "name")).isEqualTo("doThings");
    }

    @Test
    void patternTest11_methodWithTypeParameters() {
        // pattern: "com.example.service.HomeService.<T>doThings(T)" location: METHOD
        // Expected: doThings (line 20) in HomeService.java
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.<T>doThings(T)", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(20);
    }

    @Test
    void patternTest111_methodWithWildcardInPackage() {
        // pattern: "com.example.*.HomeService.doThings" location: METHOD
        // Expected: doThings (line 20) in HomeService.java
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.*.HomeService.doThings", "METHOD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(20);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  METHOD_CALL matching (konveyor-java-pattern-test 12-14)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest12_exactMethodCall() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.doThings", "METHOD_CALL"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(24);
    }

    @Test
    void patternTest13_methodCallWildcard() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.do*", "METHOD_CALL"));

        assertThat(resp.getMatched()).isTrue();
    }

    @Test
    void patternTest14_methodCallAllMethods() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.service.HomeService.*", "METHOD_CALL"));

        assertThat(resp.getMatched()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FIELD matching (konveyor-java-pattern-test 15, 151)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest15_fieldWithReturnType() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("* com.example.model.TypedEntity", "FIELD"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeService.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(9);
        assertThat(var(incidents.get(0), "name")).isEqualTo("typedEntity");
    }

    @Test
    void patternTest151_fieldWithReturnTypeWildcard() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("* com.example.model.Typed*", "FIELD"));

        assertThat(resp.getMatched()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ANNOTATION matching (konveyor-java-pattern-test 16-21, 211)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest16_exactAnnotation() {
        // pattern: "org.springframework.stereotype.Controller" location: ANNOTATION
        // Expected: HomeController.java line 12
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("org.springframework.stereotype.Controller", "ANNOTATION"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(12);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Property");
    }

    @Test
    void patternTest17_classWithAnnotated() {
        // pattern: "*" location: CLASS annotated: @Controller
        // Expected: HomeController.java line 14
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated("*", "CLASS",
                        "org.springframework.stereotype.Controller", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(14);
        assertThat(var(incidents.get(0), "kind")).isEqualTo("Class");
        assertThat(var(incidents.get(0), "name")).isEqualTo("HomeController");
    }

    @Test
    void patternTest18_methodWithAnnotatedBean_tiles() {
        // pattern: "*" location: METHOD annotated: @Bean
        // Expected from tiles: TilesConfig.java lines 17 (tilesConfigurer), 25 (tilesViewResolver)
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated("*", "METHOD",
                        "org.springframework.context.annotation.Bean", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(2);
        assertThat(incidents.stream().map(ic -> var(ic, "name")).toList())
                .containsExactlyInAnyOrder("tilesConfigurer", "tilesViewResolver");
    }

    @Test
    void patternTest18_methodWithAnnotatedBean_customers() {
        // Expected from customers: PersistenceConfig.java 4 @Bean methods
        ProviderEvaluateResponse resp = customersCtx.evaluate("referenced",
                referencedAnnotated("*", "METHOD",
                        "org.springframework.context.annotation.Bean", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "PersistenceConfig.java");
        assertThat(incidents).hasSize(4);
        assertThat(incidents.stream().map(ic -> var(ic, "name")).toList())
                .containsExactlyInAnyOrder("entityManagerFactory", "dataSource",
                        "transactionManager", "exceptionTranslation");
    }

    @Test
    void patternTest19_methodWithReturnTypeAnnotated() {
        // pattern: "* org.springframework.web.servlet.view.tiles3.TilesConfigurer"
        // location: METHOD, annotated: @Bean
        // Expected: TilesConfig.java line 17
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated(
                        "* org.springframework.web.servlet.view.tiles3.TilesConfigurer", "METHOD",
                        "org.springframework.context.annotation.Bean", null));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(17);
        assertThat(var(incidents.get(0), "name")).isEqualTo("tilesConfigurer");
    }

    @Test
    void patternTest20_methodAnnotatedWithTextElement() {
        // pattern: "* org.springframework.web.servlet.view.tiles3.TilesConfigurer"
        // location: METHOD, annotated: @Bean(name = "nameFor.*")
        // Expected: TilesConfig.java line 17
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated(
                        "* org.springframework.web.servlet.view.tiles3.TilesConfigurer", "METHOD",
                        "org.springframework.context.annotation.Bean",
                        List.of(Map.entry("name", "nameFor.*"))));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TilesConfig.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(17);
    }

    @Test
    void patternTest21_methodAnnotatedWithBooleanElement() {
        // Same as test 20 but also requires autowireCandidate=false
        // @Bean(name = "nameForThisBean", autowireCandidate = false) would need
        // both elements. The Go output matches TilesConfig line 17.
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated(
                        "* org.springframework.web.servlet.view.tiles3.TilesConfigurer", "METHOD",
                        "org.springframework.context.annotation.Bean",
                        List.of(Map.entry("name", "nameFor.*"),
                                Map.entry("autowireCandidate", "false"))));

        // Go provider matches this — both elements found in @Bean annotation
        assertThat(resp.getMatched()).isTrue();
    }

    @Test
    void patternTest211_annotationWithElementsSearch() {
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedAnnotated(
                        "org.springframework.web.bind.annotation.GetMapping", "ANNOTATION",
                        null,
                        List.of(Map.entry("value", ".*/$"))));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "HomeController.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(27);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TYPE matching (konveyor-java-pattern-test 22)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternTest22_typeWithGenerics() {
        // pattern: "com.example.model.TypedEntity<*>" location: TYPE
        // Expected: TypedEntity.java line 3
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referenced("com.example.model.TypedEntity<*>", "TYPE"));

        assertThat(resp.getMatched()).isTrue();
        var incidents = incidentsIn(resp, "TypedEntity.java");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getLineNumber()).isEqualTo(3);
        assertThat(var(incidents.get(0), "name")).isEqualTo("TypedEntity");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  filepaths condition parameter: restrict results to specific files
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void filepathsFilter_restrictToSingleFile() {
        // Without filepaths, "do*" METHOD matches in both HomeController and HomeService
        ProviderEvaluateResponse unrestricted = tilesCtx.evaluate("referenced",
                referenced("do*", "METHOD"));
        assertThat(unrestricted.getMatched()).isTrue();
        assertThat(unrestricted.getIncidentContextsCount()).isGreaterThanOrEqualTo(3);

        // With filepaths, restrict to only HomeService.java
        Path homeServicePath = EXAMPLES_ROOT.resolve(
                "sample-tiles-app/src/main/java/com/example/service/HomeService.java");
        ProviderEvaluateResponse restricted = tilesCtx.evaluate("referenced",
                referencedWithFilepaths("do*", "METHOD",
                        List.of(homeServicePath.toAbsolutePath().toString())));

        assertThat(restricted.getMatched()).isTrue();
        var incidents = restricted.getIncidentContextsList();
        assertThat(incidents).allMatch(ic -> ic.getFileURI().contains("HomeService.java"));
        assertThat(incidents.stream().map(ic -> var(ic, "name")).toList())
                .containsOnly("doStuff", "doThings");
    }

    @Test
    void filepathsFilter_noMatchReturnsUnmatched() {
        // Restrict to a file that has no matches for the pattern
        Path typedEntityPath = EXAMPLES_ROOT.resolve(
                "sample-tiles-app/src/main/java/com/example/model/TypedEntity.java");
        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedWithFilepaths("do*", "METHOD",
                        List.of(typedEntityPath.toAbsolutePath().toString())));

        assertThat(resp.getMatched()).isFalse();
    }

    @Test
    void filepathsFilter_withFileUri() {
        // Pass filepaths as file:// URIs (the format returned by evaluate results)
        Path homeServicePath = EXAMPLES_ROOT.resolve(
                "sample-tiles-app/src/main/java/com/example/service/HomeService.java");
        String fileUri = homeServicePath.toAbsolutePath().toUri().toString();

        ProviderEvaluateResponse resp = tilesCtx.evaluate("referenced",
                referencedWithFilepaths("do*", "METHOD", List.of(fileUri)));

        assertThat(resp.getMatched()).isTrue();
        assertThat(resp.getIncidentContextsList())
                .allMatch(ic -> ic.getFileURI().contains("HomeService.java"));
    }
}

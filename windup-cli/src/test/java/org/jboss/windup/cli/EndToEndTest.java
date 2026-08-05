package org.jboss.windup.cli;

import jakarta.enterprise.inject.Instance;

import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.DynamicRuleProviderRegistry;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.RuleEngine;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderSorter;
import org.jboss.windup.engine.archive.ArchiveExtractionProvider;
import org.jboss.windup.engine.discovery.FileDiscoveryProvider;
import org.jboss.windup.java.ast.JavaASTParser;
import org.jboss.windup.java.ast.JavaASTRuleProvider;
import org.jboss.windup.java.decompiler.DecompilationProvider;
import org.jboss.windup.java.decompiler.DecompilerService;
import org.jboss.windup.java.model.JavaClassModel;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.java.scan.JavaClassScanProvider;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.jboss.windup.model.ModelRegistry;
import org.jboss.windup.reporting.ViolationOutputProvider;
import org.jboss.windup.reporting.model.InlineHintModel;
import org.jboss.windup.reporting.output.ViolationOutputWriter;
import org.jboss.windup.rules.loader.YamlRuleActionFactory;
import org.jboss.windup.rules.loader.YamlRuleConditionFactory;
import org.jboss.windup.rules.loader.YamlRuleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that exercises the full Windup analysis pipeline
 * on a sample Java project:
 * <ol>
 *   <li>FileDiscoveryProvider (DISCOVERY) - walks filesystem, creates FileModels</li>
 *   <li>ArchiveExtractionProvider (ARCHIVE_EXTRACTION) - extracts JARs/WARs</li>
 *   <li>JavaASTRuleProvider (INITIAL_ANALYSIS) - parses Java source with JDT</li>
 *   <li>JavaClassScanProvider (INITIAL_ANALYSIS) - scans .class bytecode</li>
 *   <li>DecompilationProvider (DECOMPILATION) - decompiles .class to .java</li>
 *   <li>YAML migration rules (MIGRATION_RULES) - loaded from test-rules/</li>
 *   <li>ViolationOutputProvider (REPORT_RENDERING) - writes output.yaml</li>
 * </ol>
 */
class EndToEndTest {

    private static final Path OUTPUT_DIR = Path.of("target", "e2e-output");

    private Path outputDir;
    private Path testProjectDir;
    private Path testRulesDir;

    @BeforeEach
    void setUp() throws Exception {
        outputDir = OUTPUT_DIR;
        if (Files.exists(outputDir)) {
            try (var walk = Files.walk(outputDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(outputDir);

        testProjectDir = resolveResource("test-project");
        testRulesDir = resolveResource("test-rules");
    }

    @Test
    void fullPipelineProducesExpectedResults() throws Exception {
        // --- 1. Build the analysis configuration ---
        AnalysisConfiguration config = AnalysisConfiguration.builder()
                .inputPath(testProjectDir)
                .outputDirectory(outputDir)
                .userRulesPath(testRulesDir)
                .sourceMode(true)
                .build();

        // --- 2. Create the analysis run ---
        AnalysisContext context = new AnalysisContext();
        AnalysisRun run = new AnalysisRun(context, config);

        // --- 3. Create all built-in providers ---
        List<RuleProvider> builtInProviders = createBuiltInProviders();

        // --- 4. Load YAML rules ---
        YamlRuleConditionFactory conditionFactory = new YamlRuleConditionFactory();
        YamlRuleActionFactory actionFactory = new YamlRuleActionFactory();
        YamlRuleLoader yamlLoader = new YamlRuleLoader(conditionFactory, actionFactory);
        List<RuleProvider> yamlProviders = yamlLoader.loadRules(testRulesDir);

        assertThat(yamlProviders)
                .as("YAML rules should be loaded from test-rules directory")
                .isNotEmpty();

        // --- 5. Combine all providers and create the engine ---
        List<RuleProvider> allProviders = new ArrayList<>(builtInProviders);
        allProviders.addAll(yamlProviders);

        RuleEngine engine = createEngine(allProviders);

        // --- 6. Execute the full pipeline ---
        engine.execute(run);

        // --- 7. Verify: FileModels were created for .java source files ---
        verifyFileDiscovery(context);

        // --- 8. Verify: JavaClassModels were extracted by AST parser ---
        verifyJavaClassModels(context);

        // --- 9. Verify: JavaClassReferences were found ---
        verifyJavaClassReferences(context);

        // --- 10. Verify: InlineHintModels were created by rule actions ---
        verifyInlineHints(context);

        // --- 11. Verify: output.yaml was generated ---
        verifyOutputYaml();
    }

    // ---------------------------------------------------------------
    // Verification methods
    // ---------------------------------------------------------------

    private void verifyFileDiscovery(AnalysisContext context) {
        assertThat(context.files().size())
                .as("File discovery should find files in the test project")
                .isGreaterThan(0);

        List<FileModel> javaSourceFiles = context.getFilesByType(FileType.JAVA_SOURCE);
        assertThat(javaSourceFiles)
                .as("Should discover .java source files")
                .isNotEmpty();

        assertThat(javaSourceFiles)
                .as("Should discover exactly 5 Java source files")
                .hasSize(5);

        List<String> javaFileNames = javaSourceFiles.stream()
                .map(FileModel::getFileName)
                .toList();
        assertThat(javaFileNames)
                .as("Should discover the expected Java source files")
                .containsExactlyInAnyOrder(
                        "DemoApplication.java",
                        "HelloService.java",
                        "AppConfig.java",
                        "LocationTestService.java",
                        "AnotherTestService.java");
    }

    private void verifyJavaClassModels(AnalysisContext context) {
        ModelRegistry<JavaClassModel> classRegistry = context.getOrCreateRegistry(JavaClassModel.class);
        List<JavaClassModel> javaClasses = classRegistry.findAll();

        assertThat(javaClasses)
                .as("AST parser should extract Java class models")
                .isNotEmpty();

        List<String> classNames = javaClasses.stream()
                .map(JavaClassModel::getQualifiedName)
                .toList();

        assertThat(classNames)
                .as("Should extract the expected classes from source files")
                .contains(
                        "com.example.demo.DemoApplication",
                        "com.example.demo.HelloService",
                        "com.example.demo.config.AppConfig",
                        "com.example.demo.LocationTestService",
                        "com.example.demo.AnotherTestService");
    }

    private void verifyJavaClassReferences(AnalysisContext context) {
        ModelRegistry<JavaClassReference> refRegistry = context.getOrCreateRegistry(JavaClassReference.class);
        List<JavaClassReference> references = refRegistry.findAll();

        assertThat(references)
                .as("Type reference collector should find Java class references")
                .isNotEmpty();

        List<String> importRefs = references.stream()
                .filter(r -> r.getReferenceType() == JavaClassReference.ReferenceType.IMPORT)
                .map(JavaClassReference::getQualifiedName)
                .toList();

        assertThat(importRefs)
                .as("Should find javax.ejb import references")
                .anyMatch(name -> name.startsWith("javax.ejb."));

        assertThat(importRefs)
                .as("Should find javax.persistence import references")
                .anyMatch(name -> name.startsWith("javax.persistence."));

        assertThat(importRefs)
                .as("Should find javax.xml.bind import references")
                .anyMatch(name -> name.startsWith("javax.xml.bind."));
    }

    private void verifyInlineHints(AnalysisContext context) {
        ModelRegistry<InlineHintModel> hintRegistry = context.getOrCreateRegistry(InlineHintModel.class);
        List<InlineHintModel> hints = hintRegistry.findAll();

        assertThat(hints)
                .as("YAML migration rules should produce inline hints")
                .isNotEmpty();

        // --- Jakarta migration rules ---

        assertThat(hints)
                .as("Should have hints for javax.ejb annotations (@Stateless, @LocalBean)")
                .anyMatch(h -> ruleIdContains(h, "javax-ejb-annotations"));

        assertThat(hints)
                .as("Should have hints for javax.persistence annotations (@Entity, @Id, @Table)")
                .anyMatch(h -> ruleIdContains(h, "javax-persistence-annotations"));

        assertThat(hints)
                .as("Should have hints for javax.xml.bind annotations (@XmlRootElement)")
                .anyMatch(h -> ruleIdContains(h, "javax-xml-bind-annotations"));

        assertThat(hints)
                .as("Should have hints for javax.xml.bind imports")
                .anyMatch(h -> ruleIdContains(h, "javax-xml-bind-api-usage"));

        // --- Location test rules ---

        String[] locationRuleIds = {
                "test-import",
                "test-annotation",
                "test-type",
                "test-inheritance",
                "test-implements-type",
                "test-field-declaration",
                "test-return-type",
                "test-method-parameter",
                "test-throws-method-declaration",
                "test-variable-declaration",
                "test-constructor-call",
                "test-method-call",
                "test-instance-of",
                "test-throw-statement",
                "test-catch-exception-statement",
        };

        for (String ruleId : locationRuleIds) {
            assertThat(hints)
                    .as("Location rule '%s' should produce at least one hint", ruleId)
                    .anyMatch(h -> ruleIdContains(h, ruleId));
        }

        // --- Star import resolution ---

        List<InlineHintModel> annotationHintsForAnotherService = hints.stream()
                .filter(h -> ruleIdContains(h, "test-annotation"))
                .filter(h -> h.getSourceFile().getFileName().equals("AnotherTestService.java"))
                .toList();
        assertThat(annotationHintsForAnotherService)
                .as("Star-imported @LegacyAnnotation in AnotherTestService should be detected")
                .isNotEmpty();

        // --- Cross-checks ---

        List<InlineHintModel> ejbAnnotationHints = hints.stream()
                .filter(h -> ruleIdContains(h, "javax-ejb-annotations"))
                .toList();
        assertThat(ejbAnnotationHints)
                .as("EJB annotation rule should match only annotation usages, not imports")
                .allMatch(h -> h.getTitle().contains("annotation"));

        List<InlineHintModel> xmlBindImportHints = hints.stream()
                .filter(h -> ruleIdContains(h, "javax-xml-bind-api-usage"))
                .toList();
        assertThat(xmlBindImportHints)
                .as("JAXB import rule should match import references")
                .isNotEmpty();

        // --- Every hint has meaningful content ---

        for (InlineHintModel hint : hints) {
            assertThat(hint.getTitle()).as("Each hint should have a title").isNotBlank();
            assertThat(hint.getHint()).as("Each hint should have a message body").isNotBlank();
            assertThat(hint.getSourceFile()).as("Each hint should reference a source file").isNotNull();
            assertThat(hint.getEffort()).as("Each hint should have an effort level").isNotNull();
        }
    }

    private void verifyOutputYaml() throws Exception {
        Path outputYaml = outputDir.resolve("output.yaml");
        assertThat(outputYaml)
                .as("output.yaml should be generated")
                .exists();

        String content = Files.readString(outputYaml);

        assertThat(content)
                .as("output.yaml should contain jakarta-migration ruleset")
                .contains("jakarta-migration");

        assertThat(content)
                .as("output.yaml should contain location-tests ruleset")
                .contains("location-tests");

        assertThat(content)
                .as("output.yaml should contain violation rule IDs")
                .contains("javax-ejb-annotations:")
                .contains("test-import:");

        assertThat(content)
                .as("output.yaml should contain incidents with file URIs")
                .contains("uri: \"file://");

        assertThat(content)
                .as("output.yaml should contain incident messages")
                .contains("message:");

        assertThat(content)
                .as("output.yaml should contain category")
                .contains("category:");

        assertThat(content)
                .as("output.yaml should contain effort")
                .contains("effort:");
    }

    private static boolean ruleIdContains(InlineHintModel hint, String fragment) {
        return hint.getRuleId() != null && hint.getRuleId().contains(fragment);
    }

    // ---------------------------------------------------------------
    // Wiring helpers
    // ---------------------------------------------------------------

    private List<RuleProvider> createBuiltInProviders() {
        List<RuleProvider> providers = new ArrayList<>();

        providers.add(new FileDiscoveryProvider());
        providers.add(new ArchiveExtractionProvider());

        JavaASTParser parser = new JavaASTParser();
        providers.add(new JavaASTRuleProvider(parser));

        providers.add(new JavaClassScanProvider());

        DecompilationProvider decompProvider = createDecompilationProvider();
        providers.add(decompProvider);

        // Violation output (replaces HTML/CSV reporting)
        ViolationOutputWriter outputWriter = new ViolationOutputWriter();
        providers.add(new ViolationOutputProvider(outputWriter));

        return providers;
    }

    private DecompilationProvider createDecompilationProvider() {
        DecompilerService noOpDecompiler = new DecompilerService() {
            @Override
            public Optional<String> decompile(Path classFile) {
                return Optional.empty();
            }

            @Override
            public Map<String, String> decompileArchive(Path archivePath, Path outputDir) {
                return Map.of();
            }
        };

        DecompilationProvider provider = new DecompilationProvider();
        injectField(provider, "decompilerService", noOpDecompiler);
        return provider;
    }

    private RuleEngine createEngine(List<RuleProvider> providerList) {
        RuleEngine engine = new RuleEngine();
        injectField(engine, "providers", new SimpleInstance<>(providerList));
        injectField(engine, "sorter", new RuleProviderSorter());
        injectField(engine, "dynamicRegistry", new DynamicRuleProviderRegistry());
        return engine;
    }

    private Path resolveResource(String resourceName) {
        var url = getClass().getClassLoader().getResource(resourceName);
        if (url == null) {
            throw new IllegalStateException("Test resource not found: " + resourceName);
        }
        return Path.of(url.getPath());
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to inject field '" + fieldName + "' on " + target.getClass().getSimpleName(), e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found in class hierarchy of " + clazz.getName());
    }

    // ---------------------------------------------------------------
    // Minimal Instance<T> implementation for test wiring
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static class SimpleInstance<T> implements Instance<T> {
        private final List<T> items;

        SimpleInstance(List<T> items) {
            this.items = items;
        }

        @Override
        public Iterator<T> iterator() {
            return items.iterator();
        }

        @Override
        public Stream<T> stream() {
            return items.stream();
        }

        @Override
        public T get() {
            return items.isEmpty() ? null : items.get(0);
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype,
                                                  Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return items.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return items.size() > 1;
        }

        @Override
        public boolean isResolvable() {
            return items.size() == 1;
        }

        @Override
        public void destroy(T instance) {
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}

# Windup Modernization Plan

## Executive Summary

This document proposes a complete architectural modernization of Windup, replacing its three foundational dependencies — JBoss Forge Furnace, OCPSoft Rewrite, and JanusGraph — with modern alternatives. The project is deprecated and not in active use, which gives us freedom to make breaking changes and redesign from first principles.

**Key replacements:**

| Current | Replacement | Rationale |
|---------|-------------|-----------|
| JBoss Forge Furnace 2.29.1 | Quarkus 3.x | Red Hat's flagship framework; CDI-based (eases migration); fast startup; native compilation |
| OCPSoft Rewrite 3.5.1 | Custom lightweight rule engine | Windup uses <5% of Rewrite's surface; a purpose-built engine is simpler and more maintainable |
| JanusGraph 0.6.3 + Ferma + BerkeleyJE | In-memory POJO model (Java records) | Dramatic simplification; the data volumes don't justify a graph database |
| 34 Maven modules | ~7 modules | Furnace's addon system forced artificial splits; Quarkus doesn't need them |

---

## Current State: Integration Depth

Before diving into the plan, here is the measured integration depth of each dependency. These numbers define the scope of work.

### Furnace

| Metric | Count |
|--------|-------|
| Java files with Furnace/Forge imports | 217 (12.6%) |
| pom.xml files with `forge-addon` classifier | 73 (65.8%) |
| `@Inject` sites (all Furnace CDI) | 215 |
| `@AddonDependency` test annotations | 1,322 |
| `beans.xml` files | 66 |
| Modules with addon/api/impl pattern | 9 |
| Test files using Furnace Arquillian | 214 |

### OCPSoft Rewrite

| Metric | Count |
|--------|-------|
| Java files importing OCPSoft types | 376 (24% of production code) |
| Distinct OCPSoft types used | 62 |
| `ConfigurationBuilder.begin()` call sites | 137 |
| Classes extending/implementing OCPSoft types | ~30 |
| Rewrite artifacts used | 2 (`rewrite-api`, `rewrite-impl`) |

### JanusGraph / TinkerPop / Ferma

| Metric | Count |
|--------|-------|
| Files importing `org.jboss.windup.graph` | 573 (33%) |
| Model interfaces (`@TypeValue`) | 177 |
| `@Property` annotation usages | 501 |
| `@Adjacency` annotation usages | 359 |
| `GraphService` subclasses | 58 |
| `new GraphService<>()` instantiation sites | 259 |
| Files with raw Gremlin traversals | 66 |
| Modules with graph dependency | 22 of 32 |

---

## Architectural Vision

### Why Replace the Graph Database?

The graph database appears to have been chosen because the data model is naturally a graph: files belong to projects, projects have dependencies, type references link to classes, classifications attach to files, etc. This is a valid model, but JanusGraph is massive overkill:

- **Data volumes are modest.** Even a large enterprise application produces tens of thousands of files and type references, not millions. This fits comfortably in memory on any modern machine.
- **The complexity cost is enormous.** 177 model interfaces with 860+ annotation-wired properties, Ferma framing with ByteBuddy proxy generation, BerkeleyJE storage backend, Lucene indexing, graph transaction management — all to store what is essentially an object graph.
- **Disk-backed storage is unnecessary.** Windup runs as a batch process: scan, analyze, report, exit. There's no long-lived database to query between runs. The data is ephemeral.
- **An in-memory POJO model would be faster.** No serialization overhead, no disk I/O, no graph query planning, no proxy indirection.

**Replacement:** Plain Java records and classes with explicit relationships (lists, maps, sets). Query patterns replaced by indexed collections (`Map<String, List<T>>`). The 177 model interfaces become ~40-50 record/class definitions.

### Why a Custom Rule Engine?

OCPSoft Rewrite is a URL rewriting framework repurposed as a generic rule engine. Windup uses only its `config` module (2 of 15+ artifacts) — specifically the `Rule`, `Condition`, `Operation`, `Configuration`, and parameterization types. Windup has already built the entire domain-specific layer on top:

- `GraphCondition`, `GraphOperation`, `GraphRewrite` adapt Rewrite to graph contexts
- All concrete conditions (`JavaClass`, `XmlFile`, `FileContent`, `Project`, `Dependency`) are Windup code
- All concrete operations (`Hint`, `Classification`, `TechnologyTag`, `LineItem`) are Windup code
- The rule execution engine (`RuleSubset`) is Windup code
- The rule loading, sorting, and phase system is Windup code

What OCPSoft actually provides is just the interfaces and base classes — roughly 10 types that matter. A custom engine replaces these with purpose-built interfaces that are clearer, simpler, and don't carry the conceptual weight of a URL rewriting framework.

### Why Quarkus?

- **CDI compatibility.** Furnace uses CDI (via Weld) for dependency injection. Quarkus uses Arc (a CDI-lite implementation). The 215 `@Inject` sites translate nearly 1:1.
- **Red Hat alignment.** Quarkus is Red Hat's flagship Java framework, actively maintained with long-term investment.
- **Build-time optimization.** Quarkus does extensive work at build time (classpath scanning, bean discovery, proxy generation), reducing startup time.
- **Native compilation.** GraalVM native-image support is a potential future benefit for CLI tooling.
- **Extension system.** Replaces Furnace's addon system for modular architecture.

---

## Proposed Module Structure

### Current: 34 modules

```
bom, utils, graph (addon/api/impl/tests), config (addon/api/impl/tests),
config-xml (addon/tests), config-groovy (addon/tests), exec (addon/api/impl/tests),
java-ast (addon/tests), decompiler (api/impl-fernflower/impl-procyon),
rules-base (api/impl/addon/tests), rules-java (api/impl/addon/tests),
rules-java-ee (addon/tests), rules-java-archives (addon/tests),
rules-java-project (addon/tests), rules-java-diva (addon/tests),
rules-xml (api/impl/addon/tests), rules-yaml (api/tests),
rules-tattletale (addon), reporting (addon/api/impl/tests),
reporting-data (addon), pf-ui, ui (addon/tests), tooling (api/impl/addon/tests),
server-provider-spi (addon), bootstrap, bootstraps-themed (4 variants),
module-spec, test-util, test-files, tests, windup-test-harness,
forks/jdt, forks/gremlin-shaded, coverage-report
```

### Proposed: 7 modules

```
windup-bom/           Bill of Materials (unchanged concept)
windup-core/          Models, rule engine, analysis context, services
windup-java/          Java analysis: AST parsing (Eclipse JDT), decompilation
windup-rules/         All built-in rules (java, java-ee, xml, yaml, archives, tattletale, diva)
windup-reporting/     Report generation: JSON data, FreeMarker HTML, CSV export
windup-ui/            React/PatternFly frontend (unchanged, just repackaged)
windup-cli/           CLI entry point, Quarkus application bootstrap
```

**Why this structure:**

- **windup-core** absorbs: `utils`, `graph`, `config`, `config-xml`, `config-groovy`, `exec`, `module-spec`, `server-provider-spi`, `tooling`
- **windup-java** absorbs: `java-ast`, `decompiler`
- **windup-rules** absorbs: `rules-base`, `rules-java`, `rules-java-ee`, `rules-java-archives`, `rules-java-project`, `rules-java-diva`, `rules-xml`, `rules-yaml`, `rules-tattletale`
- **windup-reporting** absorbs: `reporting`, `reporting-data`
- **windup-cli** absorbs: `bootstrap`, `bootstraps-themed`, `ui`
- **windup-ui** is: `pf-ui` (React app, unchanged)
- Eliminated: `forks/jdt` (use standard Eclipse JDT), `forks/gremlin-shaded` (no longer needed), `windup-test-harness` (standard testing), `test-util` (merged into test sources), `test-files` (merged into test resources)

---

## New Architecture Design

### 1. Analysis Model (replaces JanusGraph graph)

Replace 177 `WindupVertexFrame` model interfaces with ~50 Java records/classes organized in a typed, in-memory model.

```
org.jboss.windup.model/
    AnalysisContext          -- root container, replaces GraphContext
    ApplicationModel         -- represents one analyzed application
    FileModel                -- a file (source, class, xml, yaml, etc.)
    ProjectModel             -- a project (Maven, Gradle, etc.)
    DependencyModel          -- a project dependency (GAV)
    ArchiveModel             -- a JAR/WAR/EAR archive
    
    java/
        JavaClassModel       -- a Java class/interface
        JavaSourceFileModel  -- a .java source file
        JavaClassFileModel   -- a .class bytecode file
        JavaTypeReference    -- a reference to a Java type (with location)
        JavaAnnotation       -- an annotation instance
        MavenProjectModel    -- extends ProjectModel with Maven-specific data
    
    javaee/
        EjbModel             -- EJB bean (session, message-driven, entity)
        JpaEntityModel       -- JPA entity
        JpaPersistenceUnit   -- persistence unit
        DataSourceModel      -- data source
        JmsDestinationModel  -- JMS destination
        WebServiceModel      -- JAX-RS or JAX-WS endpoint
        SpringBeanModel      -- Spring bean
        HibernateEntityModel -- Hibernate entity
        WebXmlModel          -- web.xml descriptor
        ...
    
    xml/
        XmlFileModel         -- an XML file with parsed metadata
        DoctypeModel         -- DTD doctype info
        NamespaceModel       -- XML namespace
    
    report/
        ClassificationModel  -- a classification applied to a file
        HintModel            -- an inline hint at a specific location
        TechnologyTagModel   -- a technology tag on a file
        IssueCategoryModel   -- issue category (mandatory, optional, etc.)
        LinkModel            -- a reference link
        QuickfixModel        -- an automated fix suggestion
```

**Key design principles:**

- Use Java records for immutable data (type references, annotations, links, etc.)
- Use mutable classes with builders for data that is populated incrementally (files, projects, classifications)
- Replace `@Property` annotations with plain getter/setter methods or record components
- Replace `@Adjacency` annotations with typed collection fields (`List<T>`, `Set<T>`)
- Replace `GraphService<T>` with indexed registries on `AnalysisContext`

**AnalysisContext** (replaces `GraphContext`):

```java
public class AnalysisContext {
    // Indexed storage - replaces graph vertex queries
    private final ModelRegistry<FileModel> files = new ModelRegistry<>();
    private final ModelRegistry<ProjectModel> projects = new ModelRegistry<>();
    private final ModelRegistry<JavaTypeReference> typeReferences = new ModelRegistry<>();
    private final ModelRegistry<ClassificationModel> classifications = new ModelRegistry<>();
    private final ModelRegistry<HintModel> hints = new ModelRegistry<>();
    // ...
    
    // Typed queries - replaces GraphService.findAllByProperty()
    public <T> List<T> findAll(Class<T> type) { ... }
    public <T> List<T> findByProperty(Class<T> type, String property, Object value) { ... }
    public <T> Optional<T> findUnique(Class<T> type, String property, Object value) { ... }
}
```

**ModelRegistry<T>** (replaces `GraphService<T>`):

```java
public class ModelRegistry<T> {
    private final List<T> all = new ArrayList<>();
    private final Map<String, Map<Object, List<T>>> indexes = new HashMap<>();
    
    public T create(T model) { ... }
    public List<T> findAll() { ... }
    public List<T> findByProperty(String property, Object value) { ... }
    public void addIndex(String property, Function<T, Object> extractor) { ... }
}
```

### 2. Rule Engine (replaces OCPSoft Rewrite)

A clean, purpose-built rule engine with ~10 core types.

**Core interfaces:**

```java
// The execution event - replaces GraphRewrite
public class AnalysisRun {
    private final AnalysisContext context;
    private final AnalysisConfiguration config;
    private boolean cancelled;
    // ...
}

// Rule condition - replaces org.ocpsoft.rewrite.config.Condition
@FunctionalInterface
public interface RuleCondition {
    ConditionResult evaluate(AnalysisRun run);
    
    default RuleCondition and(RuleCondition other) { ... }
    default RuleCondition or(RuleCondition other) { ... }
    default RuleCondition not() { ... }
}

// Condition result with matched items
public record ConditionResult(boolean matched, List<?> items) {
    public static ConditionResult match(List<?> items) { ... }
    public static ConditionResult noMatch() { ... }
}

// Rule action - replaces org.ocpsoft.rewrite.config.Operation
@FunctionalInterface
public interface RuleAction {
    void perform(AnalysisRun run, ConditionResult matched);
}

// A single rule - replaces org.ocpsoft.rewrite.config.Rule
public record Rule(
    String id,
    RuleCondition condition,
    RuleAction action,
    RuleMetadata metadata
) {}

// Rule metadata
public record RuleMetadata(
    Phase phase,
    Set<String> tags,
    Set<Technology> sourceTechnologies,
    Set<Technology> targetTechnologies,
    List<String> executeAfter,
    List<String> executeBefore
) {}

// Rule provider - replaces AbstractRuleProvider / ConfigurationProvider
public interface RuleProvider {
    RuleProviderMetadata getMetadata();
    List<Rule> getRules();
}
```

**Phase enum** (replaces ~17 RulePhase classes):

```java
public enum Phase {
    INITIALIZATION,
    ARCHIVE_EXTRACTION,
    FILE_CLASSIFICATION,
    PROJECT_DISCOVERY,
    DECOMPILATION,
    INITIAL_ANALYSIS,
    MIGRATION_RULES,
    POST_MIGRATION_RULES,
    REPORT_GENERATION,
    REPORT_RENDERING,
    FINALIZE
}
```

**Rule execution engine** (replaces `RuleSubset`):

```java
@ApplicationScoped
public class RuleEngine {
    @Inject
    Instance<RuleProvider> providers;       // Quarkus CDI auto-discovers all providers
    
    @Inject
    RuleProviderSorter sorter;
    
    public void execute(AnalysisRun run) {
        List<RuleProvider> sorted = sorter.sort(providers.stream().toList());
        
        for (Phase phase : Phase.values()) {
            for (RuleProvider provider : sorted) {
                if (provider.getMetadata().phase() != phase) continue;
                for (Rule rule : provider.getRules()) {
                    if (run.isCancelled()) return;
                    ConditionResult result = rule.condition().evaluate(run);
                    if (result.matched()) {
                        rule.action().perform(run, result);
                    }
                }
            }
        }
    }
}
```

**Built-in conditions** (fluent API preserved):

```java
// JavaClass condition - same fluent API, no OCPSoft base classes
public class JavaClass implements RuleCondition {
    public static JavaClass references(String pattern) { ... }
    public JavaClass at(TypeReferenceLocation location) { ... }
    public JavaClass inType(String typePattern) { ... }
    public JavaClass annotationMatches(String key, String value) { ... }
    
    @Override
    public ConditionResult evaluate(AnalysisRun run) {
        // Query the AnalysisContext for matching type references
    }
}

// XmlFile condition
public class XmlFile implements RuleCondition {
    public static XmlFile matchesXpath(String xpath) { ... }
    public XmlFile inFile(String filePattern) { ... }
    public XmlFile namespace(String prefix, String uri) { ... }
}

// Project dependency condition
public class ProjectDependency implements RuleCondition {
    public static ProjectDependency withGroupId(String groupId) { ... }
    public ProjectDependency andArtifactId(String artifactId) { ... }
    public ProjectDependency andVersion(String versionRange) { ... }
}
```

**Built-in actions** (fluent API preserved):

```java
// Hint action
public class Hint implements RuleAction {
    public static Hint titled(String title) { ... }
    public Hint withText(String text) { ... }
    public Hint withEffort(int storyPoints) { ... }
    public Hint withCategory(IssueCategory category) { ... }
    public Hint withLink(String title, String url) { ... }
    public Hint withQuickfix(Quickfix fix) { ... }
}

// Classification action
public class Classification implements RuleAction {
    public static Classification as(String title) { ... }
    public Classification withDescription(String desc) { ... }
    public Classification withEffort(int storyPoints) { ... }
}

// Technology tag action
public class TechnologyTag implements RuleAction {
    public static TechnologyTag withName(String name) { ... }
    public TechnologyTag withVersion(String version) { ... }
}
```

**Rule builder** (replaces `ConfigurationBuilder.begin()`):

```java
public class RuleBuilder {
    public static RuleBuilder create() { ... }
    
    public RuleBuilder addRule(String id) { ... }
    public RuleBuilder when(RuleCondition condition) { ... }
    public RuleBuilder perform(RuleAction action) { ... }
    public List<Rule> build() { ... }
}
```

### 3. Rule Format (replaces .windup.xml)

Since backward compatibility is not required, we can design a clean YAML-based rule format alongside the Java API. YAML is more readable than XML and better suited for configuration.

```yaml
# Example: ejb-to-jakarta.rules.yaml
id: ejb-to-jakarta
metadata:
  phase: MIGRATION_RULES
  source-technologies:
    - id: java-ee
      versions: "[6,)"
  target-technologies:
    - id: jakarta-ee
      versions: "[9,)"
  tags: [ejb, jakarta]

rules:
  - id: javax-ejb-stateless
    when:
      java-class:
        references: javax.ejb.Stateless
        at: ANNOTATION
    perform:
      hint:
        title: "javax.ejb.Stateless must be migrated to jakarta.ejb.Stateless"
        category: mandatory
        effort: 1
        message: |
          The `javax.ejb.Stateless` annotation has been moved to
          `jakarta.ejb.Stateless` in Jakarta EE 9+.
        links:
          - title: Jakarta EE Migration Guide
            url: https://jakarta.ee/resources/
        quickfix:
          type: replace
          search: javax.ejb.Stateless
          replacement: jakarta.ejb.Stateless

  - id: javax-ejb-stateful
    when:
      java-class:
        references: javax.ejb.Stateful
        at: ANNOTATION
    perform:
      hint:
        title: "javax.ejb.Stateful must be migrated to jakarta.ejb.Stateful"
        category: mandatory
        effort: 1

  - id: ejb-jar-xml-namespace
    when:
      xml-file:
        xpath: //jee:enterprise-beans
        namespace:
          jee: http://java.sun.com/xml/ns/javaee
        in-file: ejb-jar.xml
    perform:
      classification:
        title: "Java EE EJB Descriptor"
        description: "This ejb-jar.xml uses the Java EE namespace and must be updated."
        effort: 3
        category: mandatory
```

**YAML rule loader** (Quarkus CDI bean):

```java
@ApplicationScoped
public class YamlRuleLoader implements RuleProviderLoader {
    public List<RuleProvider> loadFrom(Path directory) {
        // Parse YAML files, construct RuleProvider instances
    }
}
```

**Groovy support can be re-added** via a simple `GroovyRuleLoader` if needed, but YAML covers the primary use case for external rule authoring.

### 4. Quarkus Integration

**Application bootstrap** (replaces Furnace startup + `Bootstrap.main()`):

```java
@QuarkusMain
public class WindupMain implements QuarkusApplication {
    @Inject
    WindupProcessor processor;
    
    @Override
    public int run(String... args) {
        WindupConfiguration config = parseArgs(args);
        processor.execute(config);
        return 0;
    }
}
```

**Service discovery** (replaces `Furnace.getAddonRegistry().getServices()`):

```java
@ApplicationScoped
public class RuleEngine {
    // Quarkus CDI automatically discovers all RuleProvider implementations
    @Inject
    Instance<RuleProvider> ruleProviders;
    
    // Automatically discovers all RuleProviderLoader implementations
    @Inject
    Instance<RuleProviderLoader> ruleLoaders;
}
```

**Key migration patterns:**

| Furnace pattern | Quarkus equivalent |
|-----------------|-------------------|
| `@AddonDependency` | Standard Maven dependency |
| `forge-addon` classifier | Regular JAR |
| `Furnace.getAddonRegistry().getServices(T.class)` | `@Inject Instance<T>` |
| `SimpleContainer.getFurnace()` | `@Inject` or `Arc.container()` |
| `furnace.getLockManager().performLocked()` | `@Lock` or `synchronized` |
| `FurnaceCompositeClassLoader` | Standard classpath (Quarkus handles it) |
| `beans.xml` per addon | Single `beans.xml` or Quarkus auto-discovery |
| Furnace Arquillian tests | `@QuarkusTest` with JUnit 5 |

### 5. Testing (replaces Furnace Arquillian)

Replace Furnace Arquillian with Quarkus testing:

```java
@QuarkusTest
class JavaClassConditionTest {
    @Inject
    WindupProcessor processor;
    
    @Test
    void detectsStatelessAnnotation() {
        var config = WindupConfiguration.builder()
            .inputPath(Path.of("src/test/resources/jee-example"))
            .outputPath(tempDir)
            .build();
        
        AnalysisContext result = processor.execute(config);
        
        List<HintModel> hints = result.findAll(HintModel.class);
        assertThat(hints).anyMatch(h -> h.title().contains("Stateless"));
    }
}
```

### 6. Reporting

**Keep FreeMarker** for legacy HTML reports (it works well and isn't tied to Furnace/Rewrite).

**Keep the React/PatternFly UI** — it's independent and modern.

**Simplify the JSON data extraction layer** — `reporting-data`'s `AbstractApiRuleProvider` pattern (rule providers that generate JSON) becomes simple CDI beans:

```java
@ApplicationScoped
public class JsonReportGenerator {
    @Inject
    ObjectMapper objectMapper;
    
    public void generate(AnalysisContext context, Path outputDir) {
        // Write applications.json, issues.json, technologies.json, etc.
        // Much simpler without graph queries — just iterate the model
    }
}
```

---

## Implementation: AI Agent Execution Plan

This plan is designed for execution by an AI coding agent (Claude Code). Each task is scoped to fit within a single agent session, has explicit verification criteria, and declares its dependencies so tasks can be parallelized or sequenced correctly.

### Execution Principles

1. **Atomic tasks.** Each task produces a compilable, testable increment. No task leaves the project in a broken state.
2. **Verification gates.** Every task ends with `mvn compile` (at minimum) or `mvn test` passing. The agent must not report success without running the gate.
3. **Context boundaries.** Each task lists the source files to read from the old codebase and the files to create/modify in the new one. The agent reads only what's listed — no open-ended exploration mid-task.
4. **Dependency-driven ordering.** Tasks declare `depends-on` predecessors. Independent tasks at the same level can run in parallel (separate worktrees).
5. **Progress tracking.** After completing each task, the agent updates `PROGRESS.md` at the project root with the task ID, status, and any notes.
6. **Reference, don't copy blindly.** The old codebase is a reference for domain logic and behavior. The agent should understand the intent and rewrite cleanly against the new APIs rather than mechanically transplanting code that still references removed types.

### Task Naming Convention

`P<phase>T<task>` — e.g., `P0T1` is Phase 0, Task 1.

---

### Phase 0: Project Scaffolding

> **Goal:** A Quarkus application that compiles, starts, and executes a trivial rule.

#### P0T1 — Maven multi-module skeleton

**Creates:** Root `pom.xml` + 6 module `pom.xml` files (windup-bom, windup-core, windup-java, windup-rules, windup-reporting, windup-cli). No Java source yet.

**Steps:**
1. Create root pom with `<modules>` listing, Java 17 compiler settings, Quarkus BOM import (`io.quarkus.platform:quarkus-bom:3.x`)
2. `windup-bom` — `pom` packaging, `<dependencyManagement>` for cross-module versions
3. `windup-core` — `jar` packaging, depends on Quarkus Arc (CDI), Jackson
4. `windup-java` — `jar`, depends on `windup-core`, Eclipse JDT
5. `windup-rules` — `jar`, depends on `windup-core`, `windup-java`
6. `windup-reporting` — `jar`, depends on `windup-core`, `windup-rules`, FreeMarker, Jackson
7. `windup-cli` — Quarkus application module, depends on all above, picocli extension

**Verify:** `mvn compile` succeeds (empty modules, no source).
**Depends on:** nothing
**Parallel:** yes (standalone)

#### P0T2 — Rule engine core interfaces

**Creates in `windup-core`:**
- `org.jboss.windup.engine.Phase` (enum)
- `org.jboss.windup.engine.RuleCondition` (functional interface with `and/or/not` defaults)
- `org.jboss.windup.engine.ConditionResult` (record)
- `org.jboss.windup.engine.RuleAction` (functional interface)
- `org.jboss.windup.engine.Rule` (record)
- `org.jboss.windup.engine.RuleMetadata` (record)
- `org.jboss.windup.engine.RuleProvider` (interface)
- `org.jboss.windup.engine.RuleBuilder` (fluent builder producing `List<Rule>`)

**Reference (read-only):**
- Old: `config/api/src/main/java/org/jboss/windup/config/RuleSubset.java` (understand execution semantics)
- Old: `config/api/src/main/java/org/jboss/windup/config/AbstractRuleProvider.java` (understand provider contract)
- Old: `config/api/src/main/java/org/jboss/windup/config/phase/` (understand phase ordering)

**Verify:** `mvn compile -pl windup-core`
**Depends on:** P0T1
**Parallel:** yes (with P0T3)

#### P0T3 — Analysis model foundation

**Creates in `windup-core`:**
- `org.jboss.windup.model.AnalysisContext` (root container with typed registries)
- `org.jboss.windup.model.ModelRegistry<T>` (indexed collection with `create`, `findAll`, `findByProperty`, `findUnique`)
- `org.jboss.windup.model.FileModel` (mutable class: path, fileName, sha1, md5, size, parentDirectory, project, fileType enum)
- `org.jboss.windup.model.ProjectModel` (mutable class: name, version, type, parent, children, dependencies, rootFileModel)
- `org.jboss.windup.model.ArchiveModel` (extends FileModel: archiveType enum, entries)
- `org.jboss.windup.model.DependencyModel` (record: groupId, artifactId, version, classifier, scope)
- `org.jboss.windup.model.ApplicationModel` (mutable: name, inputPaths, projectModels)

**Reference (read-only):**
- Old: `graph/api/src/main/java/org/jboss/windup/graph/model/FileModel.java` (property list)
- Old: `graph/api/src/main/java/org/jboss/windup/graph/model/ProjectModel.java`
- Old: `graph/api/src/main/java/org/jboss/windup/graph/model/ArchiveModel.java`

**Verify:** `mvn compile -pl windup-core` + unit tests for `ModelRegistry` CRUD and indexing
**Depends on:** P0T1
**Parallel:** yes (with P0T2)

#### P0T4 — Rule engine implementation + Quarkus wiring

**Creates in `windup-core`:**
- `org.jboss.windup.engine.RuleProviderSorter` (sort by phase, then `executeAfter`/`executeBefore` topological sort)
- `org.jboss.windup.engine.RuleEngine` (`@ApplicationScoped`, injects `Instance<RuleProvider>`, executes phase-by-phase)
- `org.jboss.windup.engine.AnalysisRun` (holds `AnalysisContext` + `AnalysisConfiguration` + cancellation flag)
- `org.jboss.windup.engine.AnalysisConfiguration` (input paths, output dir, source/target techs, options map)
- `org.jboss.windup.engine.WindupProcessor` (`@ApplicationScoped`, creates `AnalysisContext`, delegates to `RuleEngine`)

**Creates in `windup-cli`:**
- `org.jboss.windup.cli.WindupMain` (`@QuarkusMain`, parses args with picocli, calls `WindupProcessor`)

**Creates test in `windup-core`:**
- `RuleEngineTest` — registers a trivial `RuleProvider` that adds a `FileModel`, verifies execution order and condition/action invocation

**Reference (read-only):**
- Old: `config/api/src/main/java/org/jboss/windup/config/RuleSubset.java` (execution loop logic)
- Old: `exec/impl/src/main/java/org/jboss/windup/exec/WindupProcessorImpl.java`

**Verify:** `mvn test -pl windup-core` passes. `mvn quarkus:dev -pl windup-cli` starts and exits cleanly with `--help`.
**Depends on:** P0T2, P0T3
**Parallel:** no (needs both prior tasks)

---

### Phase 1: Analysis Model Completion

> **Goal:** All model types exist. The full schema of what Windup can discover is representable.

#### P1T1 — Java models

**Creates in `windup-core` under `org.jboss.windup.model.java`:**
- `JavaClassModel` (qualifiedName, packageName, simpleName, superClass, interfaces, methods, fields, sourceFile, classFile, isPublic, isAbstract, isInterface)
- `JavaSourceFileModel` (extends FileModel: packageName, javaClasses, imports)
- `JavaClassFileModel` (extends FileModel: javaClass, majorVersion, minorVersion)
- `JavaMethodModel` (name, returnType, parameters, annotations)
- `JavaTypeReference` (record: qualifiedName, packageName, className, methodName, location enum, resolutionStatus enum, lineNumber, column, length, sourceLine, sourceFile)
- `TypeReferenceLocation` (enum — port all 18 values from old `data/TypeReferenceLocation.java`)
- `ResolutionStatus` (enum: RESOLVED, RECOVERED, UNRESOLVED, UNKNOWN)
- `JavaAnnotation` (record: qualifiedName, keyValues map)
- `MavenProjectModel` (extends ProjectModel: groupId, artifactId, mavenVersion, packaging, parentGAV, dependencyManagement)
- `MavenCoordinate` (record: groupId, artifactId, version, classifier, packaging)

**Reference:** Old `rules-java/api/src/main/java/org/jboss/windup/rules/apps/java/model/` (all model files)
**Verify:** `mvn compile -pl windup-core`
**Depends on:** P0T3
**Parallel:** yes (with P1T2, P1T3, P1T4)

#### P1T2 — XML models

**Creates in `windup-core` under `org.jboss.windup.model.xml`:**
- `XmlFileModel` (extends FileModel: rootTagName, doctypes, namespaces, publicId, systemId)
- `DoctypeModel` (record: publicId, systemId, name, baseURI)
- `NamespaceModel` (record: prefix, uri, schemaLocation)

**Reference:** Old `rules-xml/api/src/main/java/org/jboss/windup/rules/apps/xml/model/`
**Verify:** `mvn compile -pl windup-core`
**Depends on:** P0T3
**Parallel:** yes

#### P1T3 — Java EE models

**Creates in `windup-core` under `org.jboss.windup.model.javaee`:**
- `EjbBeanModel` (name, ejbClass, ejbType enum [STATELESS, STATEFUL, MESSAGE_DRIVEN, ENTITY], localInterface, remoteInterface, jndiName)
- `JpaEntityModel` (qualifiedName, tableName, entityName, persistenceUnit)
- `JpaPersistenceUnitModel` (name, jtaDataSource, nonJtaDataSource, entityClasses, properties)
- `JpaConfigurationFileModel` (extends XmlFileModel: persistenceUnits)
- `DataSourceModel` (name, jndiName, databaseTypeName, connectionUrl)
- `JmsDestinationModel` (name, jndiName, destinationType enum [QUEUE, TOPIC])
- `JndiResourceModel` (name, jndiName)
- `WebServiceModel` (qualifiedName, implementor, endpointUrl, webServiceType enum [JAX_RS, JAX_WS])
- `SpringBeanModel` (name, beanClass, qualifier, scope)
- `SpringConfigurationFileModel` (extends XmlFileModel: springBeans)
- `HibernateEntityModel` (qualifiedName, tableName)
- `HibernateConfigFileModel` (extends XmlFileModel: sessionFactoryName, dialect, dataSourceJndi, entities)
- `WebXmlModel` (extends XmlFileModel: displayName, servlets, filters, listeners)
- `RmiServiceModel` (qualifiedName, interfaceName)
- `RemoteEjbModel` (beanName, remoteInterface, jndiName)
- `JbpmProcessModel` (processName, processId)

**Reference:** Old `rules-java-ee/addon/src/main/java/org/jboss/windup/rules/apps/javaee/model/` (all model files — there are ~42)
**Verify:** `mvn compile -pl windup-core`
**Depends on:** P0T3, P1T1, P1T2
**Parallel:** yes (with P1T4)

#### P1T4 — Report models

**Creates in `windup-core` under `org.jboss.windup.model.report`:**
- `IssueCategory` (enum: MANDATORY, OPTIONAL, POTENTIAL, INFORMATION, CLOUD_MANDATORY, CLOUD_OPTIONAL)
- `LinkModel` (record: title, url)
- `QuickfixModel` (record: name, type enum [REPLACE, DELETE, INSERT, TRANSFORMATION], search, replacement, newlines)
- `ClassificationModel` (mutable: title, description, effort, issueCategory, file, links, tags, quickfixes, ruleId)
- `HintModel` (mutable: title, hint, effort, issueCategory, file, lineNumber, column, length, links, quickfixes, ruleId, sourceLine)
- `TechnologyTagModel` (record: name, version, level enum [IMPORTANT, INFORMATIONAL])
- `TechnologyUsageModel` (technologyName, occurrenceCount, tags)
- `OverviewMessageModel` (record: message, project)

**Reference:** Old `reporting/api/src/main/java/org/jboss/windup/reporting/model/`
**Verify:** `mvn compile -pl windup-core`
**Depends on:** P0T3
**Parallel:** yes

#### P1T5 — AnalysisContext registries wiring

**Modifies** `AnalysisContext` to add typed registries for all model types created in P1T1-P1T4. Adds convenience query methods:
- `getFilesByType(FileType)`, `getFileByPath(String)`
- `getProjectByGAV(String, String, String)`
- `getTypeReferencesForFile(FileModel)`
- `getClassificationsForFile(FileModel)`
- `getHintsForFile(FileModel)`

**Verify:** `mvn compile -pl windup-core` + unit test exercising cross-model queries
**Depends on:** P1T1, P1T2, P1T3, P1T4
**Parallel:** no

---

### Phase 2: Core Scanners

> **Goal:** The engine can scan a real application archive and populate the model.

#### P2T1 — Utility classes

**Creates in `windup-core` under `org.jboss.windup.util`:**
- Port `ZipUtil` (archive extraction) — read old `utils/src/main/java/org/jboss/windup/util/ZipUtil.java`
- Port `PathUtil` (path normalization) — read old `utils/.../PathUtil.java`
- Port `Checks` (input validation) — read old `utils/.../Checks.java`
- Port `ExecutionStatistics` (timing) — read old `utils/.../ExecutionStatistics.java`
- Port `PackageFrequencyTrie` (package detection) — read old `utils/.../PackageFrequencyTrie.java`
- Port `Theme` / `ThemeProvider` — read old `utils/.../Theme.java`
- Port threading utilities (`WindupExecutors`) — read old `utils/.../threading/WindupExecutors.java`

These are all leaf utilities with no Furnace/Rewrite/Graph dependency. Port directly, removing only Furnace classloader references.

**Verify:** `mvn compile -pl windup-core` + unit tests for ZipUtil, PackageFrequencyTrie
**Depends on:** P0T1
**Parallel:** yes (with Phase 1 tasks)

#### P2T2 — Archive extraction scanner

**Creates in `windup-rules` under `org.jboss.windup.rules.archive`:**
- `ArchiveExtractionRuleProvider` implements `RuleProvider` (Phase: `ARCHIVE_EXTRACTION`)
- Recursively extracts WAR/EAR/JAR files to output directory
- Creates `ArchiveModel` and child `FileModel` entries in the `AnalysisContext`
- Handles nested archives (EAR containing WAR containing JAR)

**Reference:** Old `rules-java/api/.../scan/provider/UnzipArchivesToOutputRuleProvider.java` and `rules-java/api/.../scan/operation/UnzipArchiveToOutputFolder.java`
**Verify:** `mvn test -pl windup-rules` with a test that extracts a small JAR and verifies FileModel creation
**Depends on:** P0T4, P1T5, P2T1
**Parallel:** yes (with P2T3, P2T4)

#### P2T3 — File classification scanner

**Creates in `windup-rules`:**
- `FileClassificationRuleProvider` (Phase: `FILE_CLASSIFICATION`) — maps file extensions to `FileType` enum values: `.java` -> JAVA_SOURCE, `.class` -> JAVA_CLASS, `.xml` -> XML, `.yml`/`.yaml` -> YAML, `.properties` -> PROPERTIES, `.jsp` -> JSP, etc.
- Walks the extracted file tree and creates typed `FileModel` (or subclass) instances

**Reference:** Old `rules-base/api/.../files/FileMapping.java`, `rules-yaml/api/.../DiscoverYamlFilesRuleProvider.java`
**Verify:** `mvn test -pl windup-rules`
**Depends on:** P0T4, P1T5
**Parallel:** yes

#### P2T4 — Maven project discovery

**Creates in `windup-rules`:**
- `MavenProjectDiscoveryRuleProvider` (Phase: `PROJECT_DISCOVERY`) — parses `pom.xml` files found in the file tree, creates `MavenProjectModel` with GAV, dependencies, parent relationships
- Discovers parent-child project hierarchy

**Reference:** Old `rules-java/api/.../scan/provider/DiscoverMavenProjectsRuleProvider.java` (~700 lines of XML parsing)
**Verify:** `mvn test -pl windup-rules` with a multi-module Maven project as test input
**Depends on:** P0T4, P1T1, P1T5
**Parallel:** yes

#### P2T5 — Java decompiler integration

**Creates in `windup-java` under `org.jboss.windup.java.decompiler`:**
- Port `Decompiler` interface (unchanged API)
- Port `AbstractDecompiler` (thread pool, inner-class grouping)
- Port `FernflowerDecompiler` — read old `decompiler/impl-fernflower/.../FernflowerDecompiler.java`
- Port `ProcyonDecompiler` — read old `decompiler/impl-procyon/.../ProcyonDecompiler.java`
- Port `DecompilationResult`, `DecompilationListener`, `ClassDecompileRequest`
- `DecompilationRuleProvider` (Phase: `DECOMPILATION`) — decompiles `.class` files to `.java` source

**Reference:** Old `decompiler/` module (all submodules). These are well-isolated — the main changes are removing Furnace addon wiring and storing results in `AnalysisContext`.
**Verify:** `mvn test -pl windup-java` with a test that decompiles a small JAR
**Depends on:** P0T4, P1T1, P2T1
**Parallel:** yes (with P2T6)

#### P2T6 — Java AST analysis

**Creates in `windup-java` under `org.jboss.windup.java.ast`:**
- Port `ASTProcessor` — read old `java-ast/addon/.../ASTProcessor.java`
- Port `BatchASTProcessor` — read old `java-ast/addon/.../BatchASTProcessor.java`
- Port `ReferenceResolvingVisitor` (~1,271 lines) — read old `java-ast/addon/.../ReferenceResolvingVisitor.java`
- Port `WindupASTParser` (customized JDT) — read old `java-ast/addon/src/main/java/org/eclipse/jdt/core/dom/WindupASTParser.java`
- Port `WildcardImportResolver` interface
- Port `ClassReference` data class (or reuse `JavaTypeReference` record from P1T1)
- `JavaAnalysisRuleProvider` (Phase: `INITIAL_ANALYSIS`) — runs AST analysis on all Java source files, stores `JavaTypeReference` entries

**Reference:** Old `java-ast/` module. These classes have minimal Furnace dependency (only `WindupExecutors` from utils). The main change is storing results as `JavaTypeReference` in `AnalysisContext` instead of graph vertices.
**Verify:** `mvn test -pl windup-java` with a test that parses a Java source file and verifies type references
**Depends on:** P0T4, P1T1, P2T1
**Parallel:** yes (with P2T5)

#### P2T7 — XML file discovery

**Creates in `windup-rules`:**
- `XmlDiscoveryRuleProvider` (Phase: `INITIAL_ANALYSIS`) — parses XML files to extract root tag, DTD, namespaces. Creates `XmlFileModel` instances.
- Port XML utility classes: `LocationAwareContentHandler`, `DoctypeUtils`, `NamespaceUtils` — from old `utils/.../xml/`

**Reference:** Old `rules-xml/impl/.../DiscoverXmlFilesRuleProvider.java`
**Verify:** `mvn test -pl windup-rules`
**Depends on:** P0T4, P1T2, P2T1
**Parallel:** yes

#### P2T8 — Integration smoke test

**Creates in `windup-cli` test sources:**
- `SmokeTest` — a `@QuarkusTest` that:
  1. Points at `test-files/jee-example-app-1.0.0.ear` (copy from old codebase)
  2. Runs full pipeline through `WindupProcessor`
  3. Asserts: archive extracted, files classified, Maven project discovered, Java sources parsed, type references populated, XML files parsed

This is the first end-to-end verification. It catches integration issues between all Phase 2 components.

**Verify:** `mvn test -pl windup-cli`
**Depends on:** P2T2, P2T3, P2T4, P2T5, P2T6, P2T7
**Parallel:** no (integration gate)

---

### Phase 3: Rule Conditions and Actions

> **Goal:** The fluent DSL for writing rules is fully functional.

#### P3T1 — JavaClass condition

**Creates in `windup-core` under `org.jboss.windup.engine.condition`:**
- `JavaClass` implementing `RuleCondition`
- Fluent API: `JavaClass.references("pattern").at(TypeReferenceLocation.ANNOTATION).inType("typePattern").annotationMatches("key", "value")`
- Evaluates by querying `AnalysisContext` for `JavaTypeReference` entries matching the pattern

**Reference:** Old `rules-java/api/.../condition/JavaClass.java` (~600 lines). Port the matching logic; discard the `ParameterizedGraphCondition` / OCPSoft superclass hierarchy.
**Verify:** `mvn test -pl windup-core` with unit tests covering: simple regex match, location filtering, annotation matching, `inType` filtering
**Depends on:** P0T4, P1T1
**Parallel:** yes (with P3T2-P3T5)

#### P3T2 — XmlFile condition

**Creates in `windup-core` under `org.jboss.windup.engine.condition`:**
- `XmlFile` implementing `RuleCondition`
- Fluent API: `XmlFile.matchesXpath("xpath").namespace("prefix", "uri").inFile("pattern")`
- XPath evaluation, DTD matching, filename filtering

**Reference:** Old `rules-xml/api/.../condition/XmlFile.java`
**Verify:** `mvn test -pl windup-core`
**Depends on:** P0T4, P1T2
**Parallel:** yes

#### P3T3 — FileContent and File conditions

**Creates in `windup-core`:**
- `FileContent` implementing `RuleCondition` — regex matching on file contents with line/column tracking
- `FileExists` implementing `RuleCondition` — matches files by name pattern

**Reference:** Old `rules-base/api/.../files/condition/FileContent.java`, `File.java`
**Verify:** `mvn test -pl windup-core`
**Depends on:** P0T4, P0T3
**Parallel:** yes

#### P3T4 — ProjectDependency condition

**Creates in `windup-core`:**
- `ProjectDependency` implementing `RuleCondition`
- Fluent API: `ProjectDependency.withGroupId("g").andArtifactId("a").andVersion("range")`
- Version range parsing using Maven's `ComparableVersion`

**Reference:** Old `rules-java-project/addon/.../condition/Project.java`, `Artifact.java`, `Version.java`
**Verify:** `mvn test -pl windup-core`
**Depends on:** P0T4, P1T1
**Parallel:** yes

#### P3T5 — Hint, Classification, TechnologyTag actions

**Creates in `windup-core` under `org.jboss.windup.engine.action`:**
- `Hint` implementing `RuleAction` — creates `HintModel` in context, fluent builder
- `Classification` implementing `RuleAction` — creates `ClassificationModel`, fluent builder
- `TechnologyTag` implementing `RuleAction` — creates `TechnologyTagModel`
- `LineItem` implementing `RuleAction` — creates `OverviewMessageModel`

**Reference:** Old `reporting/api/.../config/classification/Classification.java`, `reporting/api/.../config/Hint.java`
**Verify:** `mvn test -pl windup-core` — test that a rule with `JavaClass` condition + `Hint` action produces the expected `HintModel`
**Depends on:** P0T4, P1T4
**Parallel:** yes

#### P3T6 — Condition composition test

**Creates test in `windup-core`:**
- Test `and()`, `or()`, `not()` combinators
- Test `RuleBuilder` building multi-rule configurations
- Test that conditions short-circuit correctly

**Verify:** `mvn test -pl windup-core`
**Depends on:** P3T1, P3T2, P3T3, P3T4, P3T5
**Parallel:** no (integration gate)

---

### Phase 4: Java EE Discovery Rules

> **Goal:** Full Java EE technology discovery on real applications.
> 
> These tasks are highly parallel — each discovers a different technology and is independent of the others.

#### P4T1 — EJB discovery (annotations + XML)

**Creates in `windup-rules` under `org.jboss.windup.rules.javaee.ejb`:**
- `EjbAnnotationDiscoveryRuleProvider` — scans for `@Stateless`, `@Stateful`, `@MessageDriven` (javax + jakarta namespaces)
- `EjbXmlDiscoveryRuleProvider` — parses `ejb-jar.xml`

**Reference:** Old `rules-java-ee/addon/.../DiscoverEjbAnnotationsRuleProvider.java`, `DiscoverEjbConfigurationXmlRuleProvider.java`
**Verify:** `mvn test -pl windup-rules`
**Depends on:** P3T1, P3T2, P3T5
**Parallel:** yes (with P4T2-P4T8)

#### P4T2 — JPA discovery

- `JpaAnnotationDiscoveryRuleProvider` — `@Entity`, `@Table`
- `JpaXmlDiscoveryRuleProvider` — `persistence.xml`

**Reference:** Old `rules-java-ee/addon/.../DiscoverJPAAnnotations...`, `DiscoverJpaConfiguration...`
**Depends on:** P3T1, P3T2, P3T5 | **Parallel:** yes

#### P4T3 — JAX-RS / JAX-WS discovery

- `JaxRsDiscoveryRuleProvider` — `@Path`
- `JaxWsDiscoveryRuleProvider` — `@WebService`

**Reference:** Old `rules-java-ee/addon/.../DiscoverJaxRsAnnotations...`, `DiscoverJaxWSAnnotations...`
**Depends on:** P3T1, P3T5 | **Parallel:** yes

#### P4T4 — Hibernate discovery

- `HibernateConfigDiscoveryRuleProvider` — `hibernate.cfg.xml`
- `HibernateMappingDiscoveryRuleProvider` — `.hbm.xml` files

**Reference:** Old `rules-java-ee/addon/.../DiscoverHibernateConfiguration...`, `DiscoverHibernateMapping...`
**Depends on:** P3T2, P3T5 | **Parallel:** yes

#### P4T5 — Spring discovery

- `SpringConfigDiscoveryRuleProvider` — Spring XML bean definitions

**Reference:** Old `rules-java-ee/addon/.../DiscoverSpringConfigurationFilesRuleProvider.java`
**Depends on:** P3T2, P3T5 | **Parallel:** yes

#### P4T6 — web.xml + Remote services discovery

- `WebXmlDiscoveryRuleProvider`
- `RemoteEjbDiscoveryRuleProvider`, `RmiDiscoveryRuleProvider`

**Reference:** Old `rules-java-ee/addon/.../DiscoverWebXml...`, `DiscoverRemoteEjb...`, `DiscoverRmi...`
**Depends on:** P3T1, P3T2, P3T5 | **Parallel:** yes

#### P4T7 — Vendor-specific descriptor discovery

- JBoss: EJB XML, web XML, legacy EJB, jBPM
- WebLogic: EJB XML, web XML
- WebSphere: EJB bindings/extensions, web XML, WS bindings/extensions
- Orion: EJB XML, web XML

**Reference:** Old `rules-java-ee/addon/.../Resolve*RuleProvider.java` (there are ~12)
**Depends on:** P3T2, P3T5 | **Parallel:** yes

#### P4T8 — Archive identification service

**Creates in `windup-rules`:**
- `ArchiveIdentificationService` interface
- `InMemoryArchiveIdentificationService` — loads `.archive-metadata.txt` files
- `LuceneArchiveIdentificationService` — Lucene index lookup
- `ArchiveIdentificationRuleProvider` (Phase: `INITIALIZATION`) — loads identification data
- `ArchiveIdentifierRuleProvider` (Phase: `ARCHIVE_EXTRACTION`) — identifies archives by SHA1

**Reference:** Old `rules-java-archives/addon/` (all files)
**Depends on:** P0T4, P1T1 | **Parallel:** yes

#### P4T9 — Java EE integration test

**Creates test in `windup-rules`:**
- Test against `jee-example-app-1.0.0.ear`: verify EJBs, JPA entities, data sources, web services, Spring beans, Hibernate entities are all discovered

**Verify:** `mvn test -pl windup-rules`
**Depends on:** P4T1-P4T8
**Parallel:** no (integration gate)

---

### Phase 5: YAML Rule Loader

> **Goal:** External rules can be authored in YAML.

#### P5T1 — YAML rule loader

**Creates in `windup-core` under `org.jboss.windup.engine.loader`:**
- `YamlRuleLoader` (`@ApplicationScoped`) — discovers `.rules.yaml` files, parses with Jackson YAML, maps to `RuleProvider` instances
- `YamlRuleSchema` — Java records matching the YAML structure (id, metadata, rules list with when/perform)
- Maps condition names (`java-class`, `xml-file`, `file-content`, `project-dependency`) to `RuleCondition` factories
- Maps action names (`hint`, `classification`, `technology-tag`) to `RuleAction` factories

**Creates sample rules:** `src/test/resources/test-rules/sample.rules.yaml`

**Verify:** `mvn test -pl windup-core` — test that YAML rules load and execute correctly
**Depends on:** P3T6
**Parallel:** yes (with Phase 4)

---

### Phase 6: Reporting

> **Goal:** Full report output (JSON, HTML, CSV).

#### P6T1 — JSON data extraction

**Creates in `windup-reporting` under `org.jboss.windup.reporting.json`:**
- DTO records matching the `pf-ui` TypeScript interfaces: `ApplicationDto`, `ApplicationIssuesDto`, `IssueDto`, `ApplicationTechnologiesDto`, `ApplicationDependenciesDto`, `FileDto`, etc.
- `JsonReportGenerator` (`@ApplicationScoped`) — iterates `AnalysisContext`, builds DTOs, serializes to `api/` directory
- `WindupJsGenerator` — generates `windup.js` with `window[]` globals

**Reference:** Old `reporting-data/addon/.../dto/` (all DTO classes) and `pf-ui/src/main/webapp/src/api/` (TypeScript interfaces — these define the contract)
**Verify:** `mvn test -pl windup-reporting` — test JSON output against expected schema
**Depends on:** P1T5, P3T5
**Parallel:** yes (with P6T2, P6T3)

#### P6T2 — FreeMarker HTML report rendering

**Creates in `windup-reporting`:**
- Port FreeMarker template configuration and rendering pipeline
- Copy `.ftl` templates from old `reporting/impl/src/main/resources/reports/templates/`
- Adapt template variable names to new model property names
- Port key FreeMarker methods (`GetProblemSummariesMethod`, `GetEffortDescriptionForPoints`, etc.)
- `HtmlReportGenerator` (`@ApplicationScoped`)

**Reference:** Old `reporting/impl/` (templates + rendering rules)
**Verify:** `mvn test -pl windup-reporting` — render a template and verify HTML output
**Depends on:** P1T4, P1T5
**Parallel:** yes

#### P6T3 — CSV export

**Creates in `windup-reporting`:**
- `CsvExportGenerator` — per-application CSV + merged `AllIssues.csv`
- Uses OpenCSV

**Reference:** Old `reporting/impl/.../ExportCSVFileRuleProvider.java`
**Verify:** `mvn test -pl windup-reporting`
**Depends on:** P1T4
**Parallel:** yes

#### P6T4 — React UI packaging

**Creates in `windup-ui/` (new module, or just copy `pf-ui/`):**
- Copy `pf-ui/src/main/webapp/` as-is
- Adjust Maven build to package the React build output into a ZIP resource
- Wire `ReportGenerator` to extract the ZIP to the output directory

**Reference:** Old `pf-ui/pom.xml` (frontend-maven-plugin config)
**Verify:** `mvn package -pl windup-ui` produces the ZIP
**Depends on:** P0T1
**Parallel:** yes (standalone)

---

### Phase 7: CLI and End-to-End Integration

> **Goal:** A working CLI application that analyzes real apps and produces reports.

#### P7T1 — CLI argument parsing

**Modifies `windup-cli`:**
- Implement full picocli command with all options: `--input`, `--output`, `--source`, `--target`, `--packages`, `--excludePackages`, `--exportCSV`, `--exportZipReport`, `--sourceMode`, `--userRulesDirectory`
- Progress monitoring (console output)
- Theming/branding support

**Reference:** Old `bootstrap/.../commands/windup/RunWindupCommand.java`
**Verify:** `mvn package -pl windup-cli` + run `java -jar` with `--help`
**Depends on:** P0T4
**Parallel:** yes (with Phase 6)

#### P7T2 — End-to-end integration tests

**Creates in `windup-cli` test sources:**
- `EndToEndJeeTest` — full analysis of `jee-example-app-1.0.0.ear`, verify: JSON reports exist, HTML reports exist, CSV reports exist, React UI extracted
- `EndToEndSourceModeTest` — source code analysis of a Maven project
- `EndToEndBinaryModeTest` — binary WAR analysis with decompilation

**Verify:** `mvn test -pl windup-cli`
**Depends on:** P6T1, P6T2, P6T3, P6T4, P7T1, P4T9
**Parallel:** no (final integration gate)

---

### Phase 8: Hardening

> **Goal:** Confidence that the new system matches the old one's behavior.

#### P8T1 — Tattletale integration (optional)

Port `TattletaleRuleProvider` if JBoss Tattletale support is desired.
**Depends on:** P0T4 | **Parallel:** yes

#### P8T2 — DIVA transaction analysis (optional)

Port `DivaLauncher` and DIVA models.
**Depends on:** P0T4, P1T3 | **Parallel:** yes

#### P8T3 — Hardcoded IP detection

Port `DiscoverHardcodedIPAddressRuleProvider`.
**Depends on:** P3T3 | **Parallel:** yes

#### P8T4 — Package name mapping

Port `PackageNameMapping` (maps package prefixes to vendor names).
**Depends on:** P0T4 | **Parallel:** yes

---

## Task Dependency Graph

```
P0T1 ──┬── P0T2 ──┐
       │          ├── P0T4 ──┬── P2T2 ──┐
       ├── P0T3 ──┘          │          │
       │                     ├── P2T3   │
       ├── P2T1 ─────────────┤          │
       │                     ├── P2T4   ├── P2T8 (smoke test)
       │                     ├── P2T5   │
       │                     ├── P2T6   │
P1T1 ──┤                     ├── P2T7 ──┘
P1T2 ──┤                     │
P1T3 ──┼── P1T5 ─────────────┘
P1T4 ──┘     │
             │
             ├── P3T1 ──┐
             ├── P3T2   │
             ├── P3T3   ├── P3T6 ──┬── P5T1
             ├── P3T4   │          │
             ├── P3T5 ──┘          ├── P4T1 ──┐
                                   ├── P4T2   │
                                   ├── P4T3   │
                                   ├── P4T4   ├── P4T9 ──┐
                                   ├── P4T5   │          │
                                   ├── P4T6   │          │
                                   ├── P4T7   │          │
                                   └── P4T8 ──┘          │
                                                         │
P6T1 ──┐                                                 │
P6T2   ├── P7T2 (end-to-end) ◄──────────────────────────┘
P6T3   │
P6T4 ──┘
P7T1 ──┘
```

**Critical path:** P0T1 → P0T2+P0T3 → P0T4 → P2T6 → P3T1 → P3T6 → P4T1 → P4T9 → P7T2

**Maximum parallelism per phase:**
- Phase 0: 2 tasks in parallel (P0T2 ∥ P0T3)
- Phase 1: 4 tasks in parallel (P1T1 ∥ P1T2 ∥ P1T3 ∥ P1T4)
- Phase 2: 6 tasks in parallel (P2T2 ∥ P2T3 ∥ P2T4 ∥ P2T5 ∥ P2T6 ∥ P2T7)
- Phase 3: 5 tasks in parallel (P3T1 ∥ P3T2 ∥ P3T3 ∥ P3T4 ∥ P3T5)
- Phase 4: 8 tasks in parallel (P4T1-P4T8)
- Phase 6: 4 tasks in parallel (P6T1 ∥ P6T2 ∥ P6T3 ∥ P6T4)

---

## AI Agent Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| **Context window overflow** on large tasks (e.g., P1T3 with 16 model types) | High | Medium | Each task is scoped to <50 files created. If a task is too large, the agent should split it and update PROGRESS.md. |
| **Hallucinating APIs** — agent invents Quarkus or JDT methods that don't exist | Medium | High | Every task ends with `mvn compile`. The agent must not report success without a green build. |
| **Drift from old behavior** — agent reimplements logic incorrectly because it summarized rather than read the original | High | High | Each task lists specific source files to read from the old codebase. The agent should read the actual file before porting, not work from memory. |
| **Cross-task inconsistency** — parallel agents create incompatible interfaces | Medium | High | P0T2 and P0T3 (core interfaces) must complete before any dependent work. `AnalysisContext` wiring (P1T5) is a single-agent gate. |
| **Model mismatch with React UI** — JSON DTOs don't match TypeScript interfaces | Medium | Medium | P6T1 must read the TypeScript interfaces in `pf-ui/src/main/webapp/src/api/` as the source of truth. |
| **In-memory model doesn't scale** for very large apps | Low | High | P2T8 smoke test uses a real EAR. If memory is a concern, add `ModelRegistry` pagination later. |
| **Eclipse JDT classpath conflicts** | Medium | Medium | If conflicts arise, shade JDT using `maven-shade-plugin` (simpler than maintaining a fork). |
| **Test data availability** — test EAR/WAR files may be large or missing | Low | Medium | Copy essential test files from old `test-files/` into new project's `src/test/resources/`. |

---

## Progress Tracking

The agent must maintain `PROGRESS.md` at the project root:

```markdown
# Modernization Progress

| Task | Status | Notes |
|------|--------|-------|
| P0T1 | done | |
| P0T2 | done | |
| P0T3 | in-progress | |
| P0T4 | blocked | waiting on P0T2, P0T3 |
| ... | | |
```

Valid statuses: `pending`, `in-progress`, `done`, `blocked`, `skipped`.

After completing each task, the agent:
1. Runs the verification gate
2. Updates PROGRESS.md
3. Notes any deviations from the plan (new files created, interfaces changed, issues discovered)

---

## Task Summary

| Phase | Tasks | Parallel slots | Gate |
|-------|:-----:|:--------------:|------|
| 0: Scaffolding | 4 | 2 | P0T4 compiles + trivial rule executes |
| 1: Analysis Model | 5 | 4 | P1T5 all models wire into AnalysisContext |
| 2: Core Scanners | 8 | 6 | P2T8 smoke test against real EAR |
| 3: Conditions/Actions | 6 | 5 | P3T6 composition test |
| 4: Java EE Rules | 9 | 8 | P4T9 integration test |
| 5: YAML Loader | 1 | 1 | YAML rules load and execute |
| 6: Reporting | 4 | 4 | Reports render correctly |
| 7: CLI + E2E | 2 | 1 | P7T2 full end-to-end passes |
| 8: Hardening | 4 | 4 | Optional tasks |
| **Total** | **43 tasks** | | |

**Critical path length:** 12 tasks (P0T1 → P0T3 → P0T4 → P2T6 → P1T5 → P3T1 → P3T6 → P4T1 → P4T9 → P6T1 → P7T1 → P7T2)

**With maximum parallelism:** ~15 sequential agent sessions (many tasks run in parallel within phases)

---

## What Gets Deleted

| Component | Lines (est.) | Why |
|-----------|:---:|-----|
| All Furnace addon wiring (`addon/` submodules, `beans.xml`, `forge-addon` classifiers) | ~5K | Replaced by Quarkus CDI |
| Graph framing layer (177 model interfaces with 860+ annotations) | ~15K | Replaced by POJOs/records |
| `GraphService` hierarchy (58 subclasses + infrastructure) | ~8K | Replaced by `ModelRegistry` |
| `GraphContext` / `GraphContextImpl` (JanusGraph setup, Ferma wiring, ByteBuddy proxies) | ~3K | Replaced by `AnalysisContext` |
| OCPSoft Rewrite adapter layer (`GraphCondition`, `GraphOperation`, `GraphRewrite`, `RuleSubset`) | ~3K | Replaced by custom rule engine |
| `ConfigurationBuilder.begin()` boilerplate in 137 files | ~2K | Replaced by `RuleBuilder` |
| Furnace bootstrap code (`Bootstrap.java`, Furnace startup, addon management commands) | ~3K | Replaced by Quarkus main |
| Furnace test infrastructure (214 test files with `@AddonDependency`) | ~10K | Replaced by `@QuarkusTest` |
| `forks/jdt`, `forks/gremlin-shaded` | ~1K | No longer needed |
| `module-spec`, `server-provider-spi`, `windup-test-harness` | ~500 | No longer needed |
| **Estimated total deleted** | **~50K+** | |

---

## What Gets Preserved

| Component | Why |
|-----------|-----|
| Eclipse JDT AST analysis (`java-ast`) | Well-written, no graph/Furnace dependency. Copy with minimal changes. |
| Decompiler wrappers (Fernflower, Procyon) | Well-isolated behind `Decompiler` interface. Copy as-is. |
| FreeMarker templates | Independent of framework. Minor property name updates. |
| React/PatternFly UI (`pf-ui`) | Completely independent frontend. Copy as-is. |
| Rule logic (conditions, operations) | The domain logic inside `JavaClass.evaluate()`, `XmlFile.evaluate()`, etc. is preserved — only the base classes change. |
| Java EE discovery logic | XML parsing and annotation scanning logic is framework-independent. Only the graph storage calls change. |
| Archive identification (Lucene index, SHA1 lookup) | Independent of graph database. Copy with minor interface changes. |
| CSV export logic | Simple iteration + serialization. |
| Streaming regex matcher (Streamflyer) | Useful utility, no framework dependency. |
| `WindupASTParser` (customized JDT parser) | Critical for analyzing older JDK apps on newer JVMs. |

---

## Summary

This modernization replaces three aging infrastructure dependencies with modern alternatives while preserving the valuable domain logic:

- **Furnace -> Quarkus:** Standard CDI injection, simpler module system, native compilation potential
- **OCPSoft Rewrite -> Custom engine:** ~10 clean interfaces replacing 62 repurposed URL-rewriting types
- **JanusGraph -> In-memory POJOs:** ~50 records/classes replacing 177 annotated graph frame interfaces, eliminating BerkeleyJE, Lucene indexing, Ferma, and ByteBuddy proxy generation
- **34 modules -> 7 modules:** Eliminating Furnace-mandated addon/api/impl splits
- **XML rules -> YAML rules:** Cleaner, more readable rule format

The result is a dramatically simpler codebase (~50K lines deleted) that is easier to understand, maintain, build, and extend — while retaining the full analytical capability of the original Windup engine.

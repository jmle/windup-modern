# Windup Modernization Plan

## Executive Summary

This document describes the architectural modernization of Windup, replacing its three foundational dependencies — JBoss Forge Furnace, OCPSoft Rewrite, and JanusGraph — with modern alternatives. The project is deprecated and not in active use, which gives us freedom to make breaking changes and redesign from first principles.

**Key replacements:**

| Current | Replacement | Rationale |
|---------|-------------|-----------|
| JBoss Forge Furnace 2.29.1 | Quarkus 3.17.5 | Red Hat's flagship framework; CDI-based (eases migration); fast startup; native compilation |
| OCPSoft Rewrite 3.5.1 | Custom lightweight rule engine | Windup uses <5% of Rewrite's surface; a purpose-built engine is simpler and more maintainable |
| JanusGraph 0.6.3 + Ferma + BerkeleyJE | In-memory POJO model (Java records) | Dramatic simplification; the data volumes don't justify a graph database |
| 34 Maven modules | 6 modules | Furnace's addon system forced artificial splits; Quarkus doesn't need them |

---

## Current State

**Version:** 7.0.0-SNAPSHOT
**Java:** 17
**Quarkus:** 3.17.5
**Build:** Jandex 3.2.3 for CDI bean discovery across modules

### What Has Been Built

| Module | Main Sources | Test Sources | Tests | Status |
|--------|:---:|:---:|:---:|--------|
| windup-bom | 0 | 0 | — | Complete |
| windup-core | 20 classes | 6 test classes | 86 passing | Complete |
| windup-java | 22 classes | 5 test classes | 53 passing | Complete |
| windup-rules | 17 classes | 5 test classes | 80 passing | Complete |
| windup-reporting | 13 classes | 3 test classes | 16 passing | Complete |
| windup-cli | 1 class | 1 test class | 1 passing | Complete |
| **Total** | **73** | **20** | **236 passing** | |

### Working CLI

The CLI is fully functional as a Quarkus application:

```bash
java -jar windup-cli/target/quarkus-app/quarkus-run.jar \
  --input <path-to-java-project> \
  --output <output-dir> \
  --userRulesDirectory <path-to-yaml-rules> \
  --sourceMode
```

Reports generated: `index.html`, `migration-issues.html`, `AllIssues.csv`.

### E2E Test Coverage

The end-to-end test (`EndToEndTest`) exercises the full pipeline with a sample Java project containing javax.ejb, javax.persistence, and javax.xml.bind usage. It verifies:

- File discovery (4 Java source files)
- Java AST parsing and class model extraction
- Type reference collection across all 15 emitted reference locations
- YAML rule loading and execution (19 rules across 2 rulesets)
- Location-filtered matching (IMPORT, ANNOTATION, TYPE, INHERITANCE, IMPLEMENTS_TYPE, FIELD_DECLARATION, RETURN_TYPE, METHOD_PARAMETER, THROWS_METHOD_DECLARATION, VARIABLE_DECLARATION, CONSTRUCTOR_CALL, METHOD_CALL, INSTANCE_OF, THROW_STATEMENT, CATCH_EXCEPTION_STATEMENT)
- Report model generation
- HTML report rendering (FreeMarker)
- CSV export

Test output persists at `windup-cli/target/e2e-output/` for manual inspection.

---

## Module Structure

```
windup-bom/           Bill of Materials — standalone POM, no parent (avoids circular dependency)
windup-core/          Models, rule engine, analysis context, file discovery, archive extraction
windup-java/          Java analysis: AST parsing (Eclipse JDT), class file scanning, decompilation, Maven POM parsing
windup-rules/         YAML rule loader, condition/action implementations, XML models
windup-reporting/     FreeMarker HTML reports, CSV export, report data collection
windup-cli/           Quarkus application with picocli CLI
```

**What each module absorbed from the old codebase:**

- **windup-core** absorbed: `utils`, `graph`, `config`, `config-xml`, `exec`, `module-spec`, `server-provider-spi`, `tooling`
- **windup-java** absorbed: `java-ast`, `decompiler`, `rules-java` (scan/maven portions)
- **windup-rules** absorbed: `rules-base`, `rules-java` (conditions), `rules-xml` (conditions), `config-xml` (YAML loader)
- **windup-reporting** absorbed: `reporting`, `reporting-data`
- **windup-cli** absorbed: `bootstrap`
- **Eliminated:** `forks/jdt`, `forks/gremlin-shaded`, `windup-test-harness`, `test-util`, `test-files`, `pf-ui`, all `addon/api/impl` splits

---

## Architecture Details

### Analysis Model (replaces JanusGraph)

177 `WindupVertexFrame` model interfaces → 23 POJO/record classes.

**windup-core models (`org.jboss.windup.model`):**

| Class | Type | Description |
|-------|------|-------------|
| `AnalysisContext` | class | Root container with typed `ModelRegistry<T>` instances and `getOrCreateRegistry(Class<T>)` for extensibility |
| `ModelRegistry<T>` | class | Generic indexed collection: `register()`, `findAll()`, `size()`, `addIndex()`, `findByIndex()`, `findUniqueByIndex()` |
| `FileModel` | class | File path, name, type, hashes, size, parent, project |
| `FileType` | enum | 15 values: JAVA_SOURCE, JAVA_CLASS, XML, YAML, PROPERTIES, etc. |
| `ArchiveModel` | class | Extends FileModel: archive type, entries, GAV coordinates |
| `ArchiveType` | enum | JAR, WAR, EAR, RAR, SAR, ZIP, OTHER |
| `ProjectModel` | class | Name, version, type, parent/children, dependencies, files |
| `ApplicationModel` | class | Name, input paths, project models |
| `DependencyModel` | record | groupId, artifactId, version, classifier, scope |

**windup-java models (`org.jboss.windup.java.model`):**

| Class | Type | Description |
|-------|------|-------------|
| `JavaClassModel` | class | Qualified name, package, super class, interfaces, methods, annotations, flags |
| `JavaSourceFileModel` | class | Extends FileModel: package name, classes, imports |
| `JavaClassReference` | class | Qualified name, reference type (19-value enum), line/column, source file |
| `JavaMethodModel` | class | Name, return type, parameters, annotations |
| `JavaAnnotationModel` | class | Annotation type, key-value map |
| `MavenProjectModel` | class | Extends ProjectModel: GAV, packaging, parent |
| `EjbBeanModel` | class | Bean name, type (STATELESS/STATEFUL/SINGLETON/MESSAGE_DRIVEN/ENTITY) |
| `JpaEntityModel` | class | Entity name, table, class, persistence unit |
| `DataSourceModel` | class | Name, JNDI, database type, connection URL |
| `JmsDestinationModel` | class | Name, JNDI, type (QUEUE/TOPIC) |
| `WebServiceModel` | class | Implementation class, URL, protocol (SOAP/REST) |
| `SpringBeanModel` | class | Bean name, class, scope |

**windup-reporting models (`org.jboss.windup.reporting.model`):**

| Class | Type | Description |
|-------|------|-------------|
| `InlineHintModel` | class | Title, hint text, effort, severity, line/column, links, source file, rule ID |
| `ClassificationModel` | class | Title, description, effort, severity, links, source file, rule ID |
| `LinkModel` | record | Title, URL |
| `TechnologyTagModel` | record | Name, level (INFORMATIONAL/IMPORTANT) |
| `ReportModel` | class | Template path, report directory, filename, title |
| `Severity` | enum | INFORMATION, OPTIONAL, TRIVIAL, COMPLEX, REDESIGN, ARCHITECTURAL |
| `EffortLevel` | enum | UNKNOWN(0), TRIVIAL(1), COMPLEX(3), REDESIGN(5), ARCHITECTURAL(7) |
| `TechnologyTagLevel` | enum | INFORMATIONAL, IMPORTANT |

### Rule Engine (replaces OCPSoft Rewrite)

62 OCPSoft types → 10 purpose-built types.

**Core interfaces (`org.jboss.windup.engine`):**

| Type | Kind | Description |
|------|------|-------------|
| `Phase` | enum | 11 execution phases: INITIALIZATION through FINALIZE |
| `RuleCondition` | @FunctionalInterface | `evaluate(AnalysisRun) → ConditionResult`, with `and()`, `or()`, `not()` combinators |
| `ConditionResult` | record | `(boolean matched, List<?> items)` with factory methods |
| `RuleAction` | @FunctionalInterface | `perform(AnalysisRun, ConditionResult)` |
| `Rule` | record | `(id, condition, action, metadata)` |
| `RuleMetadata` | record | Phase, tags, source/target technologies, ordering constraints |
| `RuleProvider` | interface | `getMetadata()` + `getRules()` |
| `RuleBuilder` | class | Fluent builder producing `List<Rule>` |
| `RuleEngine` | @ApplicationScoped | Combines CDI-discovered + dynamic providers, sorts by phase + topology, executes |
| `RuleProviderSorter` | @ApplicationScoped | Topological sort within phases using executeAfter/executeBefore |
| `DynamicRuleProviderRegistry` | @ApplicationScoped | Runtime registration for YAML-loaded providers |
| `WindupProcessor` | @ApplicationScoped | Entry point: creates context, loads user rules, invokes engine |
| `UserRuleLoader` | interface | Decouples core from rules module |

**Built-in conditions (`org.jboss.windup.rules.condition`):**

| Condition | Pattern | Location filter |
|-----------|---------|-----------------|
| `JavaClassCondition` | Glob with `{*}` (single segment) and `*` (any) | Optional `ReferenceType` enum value |
| `XmlXPathCondition` | XPath expression | Matches against XML root element |
| `FileContentCondition` | Regex pattern | Optional filename glob |

**Built-in actions (`org.jboss.windup.rules.action`):**

| Action | Creates |
|--------|---------|
| `HintAction` | `InlineHintModel` per matched reference |
| `ClassificationAction` | `ClassificationModel` per unique source file |
| `TechnologyTagAction` | `TechnologyTagModel` per unique source file |

### YAML Rule Format (replaces .windup.xml)

```yaml
rules:
  id: "ruleset-id"
  phase: MIGRATION_RULES
  source-technology:
    id: "java-ee"
    version: "[7,8]"
  target-technology:
    id: "jakarta-ee"
    version: "[9,)"
  rules:
    - id: "rule-id"
      when:
        java-class:
          references: "javax.ejb.{*}"
          location: ANNOTATION
      perform:
        hint:
          title: "Migration required"
          message: "Replace javax.ejb with jakarta.ejb"
          effort: 1
          category: mandatory
          link:
            title: "Guide"
            url: "https://example.com"
```

Supported condition types: `java-class`, `xml-matches`, `file-content`.
Supported action types: `hint`, `classification`, `technology-tag`.

### Built-in Rule Providers

These providers form the analysis pipeline and execute in phase order:

| Provider | Module | Phase | Description |
|----------|--------|-------|-------------|
| `FileDiscoveryProvider` | core | DISCOVERY | Walks input paths, creates FileModel/ArchiveModel/ProjectModel |
| `ArchiveExtractionProvider` | core | ARCHIVE_EXTRACTION | Extracts ZIP/JAR/WAR/EAR recursively with zip-slip protection |
| `JavaASTRuleProvider` | java | INITIAL_ANALYSIS | Parses .java source with Eclipse JDT, creates JavaClassModel + JavaClassReference |
| `JavaClassScanProvider` | java | INITIAL_ANALYSIS | Scans .class bytecode for type references |
| `MavenAnalysisProvider` | java | COMPOSITION | Parses pom.xml files, creates MavenProjectModel |
| `DecompilationProvider` | java | DECOMPILATION | Decompiles .class files lacking .java source |
| *(YAML rules)* | rules | MIGRATION_RULES | User-provided migration rules |
| `ReportGenerationProvider` | reporting | REPORT_GENERATION | Creates ReportModel instances |
| `ReportRenderingProvider` | reporting | REPORT_RENDERING | Renders HTML (FreeMarker) + CSV (OpenCSV) |

### Type Reference Resolution

The `TypeReferenceCollector` (ASTVisitor) resolves simple names to fully qualified names using import declarations. For example, `@Stateless` is resolved to `javax.ejb.Stateless` by cross-referencing the import `import javax.ejb.Stateless`. This applies to annotations, type usages, and static method call expressions.

15 of 18 `ReferenceType` locations are emitted by the collector. Not emitted: `METHOD` (not collected), `VARIABLE_INITIALIZER` (not collected), `TAGLIB_IMPORT` (requires JSP parsing).

---

## Implementation Progress

### Completed Phases

#### Phase 0: Project Scaffolding ✓

| Task | Description | Status |
|------|-------------|--------|
| P0T1 | Maven multi-module skeleton | Done |
| P0T2 | Rule engine core interfaces | Done |
| P0T3 | Analysis model foundation | Done |
| P0T4 | Rule engine implementation + Quarkus wiring | Done |

#### Phase 1: Analysis Model ✓

| Task | Description | Status |
|------|-------------|--------|
| P1T1 | Java models | Done |
| P1T2 | XML models | Done (in windup-rules as `XmlFileModel`, `XmlTypeReferenceModel`) |
| P1T3 | Java EE models | Done (EJB, JPA, DataSource, JMS, WebService, Spring) |
| P1T4 | Report models | Done |
| P1T5 | AnalysisContext registries wiring | Done |

#### Phase 2: Core Scanners ✓

| Task | Description | Status |
|------|-------------|--------|
| P2T1 | Utility classes | Absorbed into discovery/archive modules |
| P2T2 | Archive extraction scanner | Done (in windup-core) |
| P2T3 | File classification scanner | Done (integrated into FileDiscoveryProvider + FileTypeDetector) |
| P2T4 | Maven project discovery | Done (MavenAnalysisProvider in windup-java) |
| P2T5 | Java decompiler integration | Done (FallbackDecompiler using javap) |
| P2T6 | Java AST analysis | Done (JavaASTParser + TypeReferenceCollector) |
| P2T7 | XML file discovery | Deferred (XmlXPathCondition exists but full XML discovery not implemented) |
| P2T8 | Integration smoke test | Done (EndToEndTest) |

#### Phase 3: Rule Conditions and Actions ✓

| Task | Description | Status |
|------|-------------|--------|
| P3T1 | JavaClass condition | Done (JavaClassCondition with glob + location filter) |
| P3T2 | XmlFile condition | Done (XmlXPathCondition — simplified) |
| P3T3 | FileContent condition | Done (FileContentCondition with regex + filename filter) |
| P3T4 | ProjectDependency condition | Deferred |
| P3T5 | Hint, Classification, TechnologyTag actions | Done |
| P3T6 | Condition composition test | Done (and/or/not tested in JavaClassConditionTest) |

#### Phase 5: YAML Rule Loader ✓

| Task | Description | Status |
|------|-------------|--------|
| P5T1 | YAML rule loader | Done (YamlRuleLoader + factories, 26 tests) |

#### Phase 6: Reporting (Partial) ✓

| Task | Description | Status |
|------|-------------|--------|
| P6T1 | JSON data extraction | Deferred (no React UI integration yet) |
| P6T2 | FreeMarker HTML report rendering | Done (index.ftl + migration-issues.ftl) |
| P6T3 | CSV export | Done (CSVExportService) |
| P6T4 | React UI packaging | Deferred |

#### Phase 7: CLI + E2E ✓

| Task | Description | Status |
|------|-------------|--------|
| P7T1 | CLI argument parsing | Done (picocli with all options) |
| P7T2 | End-to-end integration tests | Done (1 comprehensive test covering full pipeline + all reference locations) |

### Remaining Work

#### Phase 4: Java EE Discovery Rules — Not Started

These rules scan for specific Java EE technologies (EJB, JPA, JAX-RS, Hibernate, Spring, etc.) by analyzing annotations and XML descriptors. The model classes exist (P1T3) but no built-in discovery rules have been written yet.

| Task | Description | Priority |
|------|-------------|----------|
| P4T1 | EJB discovery (annotations + XML) | Optional |
| P4T2 | JPA discovery | Optional |
| P4T3 | JAX-RS / JAX-WS discovery | Optional |
| P4T4 | Hibernate discovery | Optional |
| P4T5 | Spring discovery | Optional |
| P4T6 | web.xml + Remote services discovery | Optional |
| P4T7 | Vendor-specific descriptor discovery | Optional |
| P4T8 | Archive identification service | Optional |
| P4T9 | Java EE integration test | Optional |

These are all achievable as YAML rules rather than hardcoded Java providers, which was the original design intent.

#### Phase 6: Reporting — Partial

| Task | Description | Priority |
|------|-------------|----------|
| P6T1 | JSON data extraction for React UI | Optional |
| P6T4 | React UI packaging | Optional |

#### Phase 8: Hardening — Not Started

| Task | Description | Priority |
|------|-------------|----------|
| P8T1 | Tattletale integration | Optional |
| P8T2 | DIVA transaction analysis | Optional |
| P8T3 | Hardcoded IP detection | Optional (achievable as FileContentCondition rule) |
| P8T4 | Package name mapping | Optional |

---

## What Was Deleted

| Component | Lines (est.) | Replacement |
|-----------|:---:|-------------|
| Furnace addon wiring (addon submodules, beans.xml, forge-addon classifiers) | ~5K | Quarkus CDI + Jandex |
| Graph framing layer (177 model interfaces with 860+ annotations) | ~15K | 23 POJO/record classes |
| GraphService hierarchy (58 subclasses + infrastructure) | ~8K | `ModelRegistry<T>` |
| GraphContext / JanusGraph / Ferma / ByteBuddy | ~3K | `AnalysisContext` |
| OCPSoft Rewrite adapter layer | ~3K | 10 custom rule engine types |
| ConfigurationBuilder boilerplate | ~2K | `RuleBuilder` + YAML rules |
| Furnace bootstrap + test infrastructure | ~13K | Quarkus main + JUnit 5 |
| Fork modules (jdt, gremlin-shaded) | ~1K | Standard dependencies |
| **Total deleted** | **~50K+** | |

## What Was Preserved

| Component | Approach |
|-----------|----------|
| Eclipse JDT AST analysis | Reimplemented cleanly as `JavaASTParser` + `TypeReferenceCollector` |
| Decompiler support | `FallbackDecompiler` using javap; Procyon stub ready for real implementation |
| FreeMarker templates | New templates (`index.ftl`, `migration-issues.ftl`) |
| CSV export | `CSVExportService` using OpenCSV |
| Rule domain logic | Conditions/actions reimplemented against new model |
| .class bytecode scanning | `ClassFileScanner` reads constant pool directly |
| Maven POM parsing | `MavenPomParser` with property interpolation |
| Archive extraction | Recursive ZIP/JAR/WAR/EAR extraction with zip-slip protection |

---

## Key Design Decisions

1. **BOM has no parent POM** — avoids circular dependency since the root POM imports the BOM.
2. **Jandex plugin in parent POM** — all modules generate `META-INF/jandex.idx` so Quarkus discovers CDI beans in dependency JARs.
3. **`UserRuleLoader` interface in core** — decouples `WindupProcessor` from `windup-rules` at compile time; `RuleLoadingProvider` implements it via CDI.
4. **`DynamicRuleProviderRegistry`** — allows runtime registration of YAML-loaded providers alongside CDI-discovered providers.
5. **Import-based name resolution** — `TypeReferenceCollector` builds a simple→FQN map from import declarations, resolving `@Stateless` to `javax.ejb.Stateless` for pattern matching.
6. **E2E test uses `target/e2e-output/`** — persistent output directory (not `@TempDir`) so generated reports can be inspected after test runs.
7. **Glob pattern syntax** — `{*}` matches a single dot-separated segment (`[^.]+`), `*` matches anything (`.*`). This matches the original Windup pattern semantics.

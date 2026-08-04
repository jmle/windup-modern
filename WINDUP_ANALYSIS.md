# Windup Project Analysis

**Version:** 6.4.0-SNAPSHOT  
**GroupId:** `org.jboss.windup`  
**Repository:** https://github.com/windup/windup  
**License:** See LICENSE file  

Windup is an assembly of tools that support large-scale Java application modernization and migration projects. It accelerates application code analysis, supports effort estimation, accelerates code migration, and helps move applications to the cloud and containers. It is part of the [Konveyor](https://www.konveyor.io/) community.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Module Inventory](#module-inventory)
3. [Core Engine](#core-engine)
4. [Graph Database Layer](#graph-database-layer)
5. [Rule Framework](#rule-framework)
6. [Java Analysis Pipeline](#java-analysis-pipeline)
7. [Rules Subsystem](#rules-subsystem)
8. [Reporting and UI](#reporting-and-ui)
9. [CLI and Bootstrapping](#cli-and-bootstrapping)
10. [Build Infrastructure](#build-infrastructure)
11. [Testing](#testing)
12. [Key Technologies and Dependencies](#key-technologies-and-dependencies)
13. [Execution Flow](#execution-flow)
14. [Data Flow Diagram](#data-flow-diagram)

---

## Architecture Overview

Windup is a rule-based static analysis engine built on three foundational pillars:

1. **JanusGraph** (TinkerPop-compatible graph database) for storing all discovered data about analyzed applications
2. **JBoss Forge Furnace** modular container for addon-based extensibility
3. **OCPSoft Rewrite** rule engine for condition/operation matching

The system works by scanning application artifacts (source code, bytecode, archives, configuration files), building a rich graph model of the application, then matching migration rules against that graph to produce classifications, hints, effort estimates, and reports.

### High-Level Module Dependency Graph

```
bootstrap (CLI entry point)
    |
    v
exec (execution orchestration)
    |
    +---> config (rule framework + lifecycle)
    |         |
    |         +---> config-xml (XML rule parser)
    |         +---> config-groovy (Groovy rule parser)
    |
    +---> graph (JanusGraph persistence)
    |
    +---> rules-base --> rules-xml --> rules-java --> rules-java-ee
    |                                     |              |
    |                                     +---> rules-java-project
    |                                     +---> rules-java-diva
    |                                     |
    |                              rules-java-archives
    |
    +---> java-ast (Eclipse JDT source parsing)
    +---> decompiler (Fernflower / Procyon)
    |
    +---> reporting (FreeMarker HTML reports)
    +---> reporting-data (JSON extraction for PF UI)
    +---> pf-ui (React/PatternFly SPA)
    +---> ui (Forge UI command abstraction)
    |
    +---> utils (shared utilities, leaf dependency)
```

---

## Module Inventory

| # | Module | Purpose | Main Java Files | Lines (approx) |
|---|--------|---------|:---:|:---:|
| 1 | `bom` | Bill of Materials (dependency version management) | 0 | -- |
| 2 | `utils` | Shared utilities (I/O, threading, XML, theming) | 40 | ~2,620 |
| 3 | `graph` | JanusGraph persistence layer + Ferma framing | 94 | large |
| 4 | `config` | Rule framework, phases, conditions, operations | 146 | large |
| 5 | `config-xml` | XML rule file parser (`.windup.xml`) | 43 | medium |
| 6 | `config-groovy` | Groovy rule file loader (`.windup.groovy`) | 3 | small |
| 7 | `exec` | Execution orchestration (`WindupProcessor`) | 32 | medium |
| 8 | `java-ast` | Java source AST analysis (Eclipse JDT) | 17 | ~3,569 |
| 9 | `decompiler` | Bytecode decompilation (Fernflower + Procyon) | 15 | ~2,182 |
| 10 | `rules-base` | Base file-matching primitives | small | small |
| 11 | `rules-java` | Java-specific analysis rules | ~120 (api alone) | large |
| 12 | `rules-java-ee` | Java EE / Jakarta EE technology discovery | large | large |
| 13 | `rules-java-archives` | Archive identification via Lucene/SHA1 | medium | medium |
| 14 | `rules-java-project` | Project-level dependency matching | small | small |
| 15 | `rules-java-diva` | DIVA transaction analysis (IBM WALA) | medium | medium |
| 16 | `rules-xml` | XML file analysis + XPath matching | medium | medium |
| 17 | `rules-yaml` | YAML file discovery | small | small |
| 18 | `rules-tattletale` | JBoss Tattletale integration | 3 | small |
| 19 | `reporting` | FreeMarker HTML report generation | large | large |
| 20 | `reporting-data` | JSON data extraction for PatternFly UI | large | large |
| 21 | `pf-ui` | React/PatternFly frontend SPA | -- | TypeScript |
| 22 | `ui` | Forge UI command abstraction | medium | medium |
| 23 | `tooling` | RMI/programmatic API for IDE integration | 42 | ~3,118 |
| 24 | `server-provider-spi` | Server provider interface | 1 | ~25 |
| 25 | `bootstrap` | CLI main(), command parsing, Furnace startup | 31 | medium |
| 26 | `bootstraps-themed` | Branded variants (Windup, MTR, MTA, Tackle) | 4 (stubs) | -- |
| 27 | `module-spec` | JBoss Modules classpath spec for graph API | 1 | small |
| 28 | `test-util` | Selenium/HtmlUnit report validators | medium | medium |
| 29 | `test-files` | Test application artifacts (WAR, EAR, source) | 0 | -- |
| 30 | `tests` | Integration tests (Arquillian/Furnace) | 55+ | large |
| 31 | `windup-test-harness` | Arquillian extension for addon deployment | 1 | small |
| 32 | `forks/jdt` | Repackaged Eclipse JDT | 0 | -- |
| 33 | `forks/gremlin-shaded` | Shaded Gremlin dependencies | 0 | -- |
| 34 | `coverage-report` | JaCoCo aggregate coverage (profile-activated) | 0 | -- |

---

## Core Engine

### exec -- Execution Orchestration

The entry point for running Windup analysis. Orchestrates rule loading, graph setup, and sequential rule execution.

**Key types:**

- **`WindupProcessor`** (interface) -- Single method: `execute(WindupConfiguration)`. The gateway to the entire engine.
- **`WindupProcessorImpl`** -- Injects `RuleLoader` and `GraphContextFactory`. Validates config, creates a `GraphContext`, loads rules, builds a `RuleSubset`, fires `PreRulesetEvaluation`, then calls `ruleSubset.perform()`.
- **`WindupConfiguration`** -- Fluent configuration: input paths, output directory, progress monitor, source/target technologies, online/offline mode, CSV export, and arbitrary options.
- **`WindupProgressMonitor`** -- Progress tracking with `beginTask`, `worked`, `subTask`, `done`, and cancellation.
- **Rule filter predicates** -- `AndPredicate`, `NotPredicate`, `SourceAndTargetPredicate`, `TaggedRuleProviderPredicate`, `RuleProviderPhasePredicate` for composable rule filtering.

---

## Graph Database Layer

### graph -- JanusGraph Persistence

All data about analyzed applications is stored as vertices and edges in a JanusGraph database with BerkeleyJE storage backend and Lucene indexing. The Ferma framing library maps graph elements to strongly-typed Java interfaces.

**Key types:**

- **`GraphContext`** -- Central interface for graph interaction. Provides access to the underlying `JanusGraph`, the `WrappedFramedGraph<JanusGraph>`, `GraphTypeManager`, and convenience methods (`create()`, `findAll()`, `getUnique()`, `service()`, `commit()`).
- **`GraphContextFactory`** -- Factory for creating/loading `GraphContext` instances at filesystem paths.
- **`GraphContextImpl`** -- Configures JanusGraph with BerkeleyJE backend, Lucene search, custom configurations. Initializes indexes from `@Indexed` annotations. Creates the Ferma `DelegatingFramedGraph` with custom `MethodHandler`s for Windup annotations (`@MapInProperties`, `@MapInAdjacentProperties`, `@SetInProperties`, `@JavaHandler`, `@Property`, `@Adjacency`).
- **`GraphTypeManager`** -- Windup's `TypeResolver`. Manages model type registry (annotated with `@TypeValue`), stores discriminator values, resolves runtime types using ByteBuddy.
- **`WindupVertexFrame`** -- Base interface for all vertex-based model types.
- **`GraphService<T>`** -- Generic CRUD/query service for framed graph elements.

**Core models:**
- `FileModel` -- Files with path, hashes, directory structure, project associations
- `ProjectModel` -- Projects with name, version, type, parent/child hierarchy, dependencies
- `ArchiveModel`, `ApplicationModel`, `WindupConfigurationModel`, `LinkModel`, `OrganizationModel`, `LicenseModel`, `TechnologyReferenceModel`

**Custom annotations:** `@Property`, `@Adjacency`, `@Indexed`, `@MapInProperties`, `@MapInAdjacentProperties`, `@MapInAdjacentVertices`, `@SetInProperties`, `@JavaHandler`, `@TypeValue`

---

## Rule Framework

### config -- Rule Framework and Lifecycle

The heart of Windup's rule-based architecture. Defines abstractions for rules, rule providers, phases, conditions, operations, iteration, querying, metadata, and the rule loading/sorting pipeline.

**Key types:**

- **`RuleProvider`** -- Core interface extending `ConfigurationProvider<RuleLoaderContext>`.
- **`AbstractRuleProvider`** -- Base class for all rule providers. Uses `@RuleMetadata` annotation for phase, tags, and dependency ordering.
- **`RuleSubset`** -- The rule execution engine. Iterates through rules in order: evaluates conditions, performs operations, handles lifecycle listeners, logs timing, manages auto-commit, handles cancellation/halt-on-exception.
- **`GraphRewrite`** -- Execution context holding the `GraphContext` and lifecycle listeners.
- **`GraphCondition`** -- Abstract base for conditions that evaluate against the graph.
- **`GraphOperation`** -- Abstract base for operations that mutate the graph.
- **`Query`** -- Fluent API for querying the graph by type, properties, and Gremlin criteria.
- **`Iteration`** -- Provides iteration over query results with conditional logic (when/perform/otherwise).
- **`Variables`** -- Stack-based variable store for intermediate results during rule evaluation.
- **`RuleLoader` / `RuleLoaderImpl`** -- Discovers `RuleProviderLoader` implementations, checks for duplicates, sorts via `RuleProviderSorter`, handles overrides.
- **`RuleProviderLoader`** -- SPI for different rule formats (Java, XML, Groovy).

### Execution Phases

Rules execute in a strict phase order:

1. `InitializationPhase`
2. `DiscoveryPhase`
3. `ArchiveExtractionPhase`
4. `ArchiveMetadataExtractionPhase`
5. `ClassifyFileTypesPhase`
6. `DiscoverProjectStructurePhase`
7. `DecompilationPhase`
8. `InitialAnalysisPhase`
9. `MigrationRulesPhase`
10. `PostMigrationRulesPhase`
11. `PreReportGenerationPhase`
12. `ReportGenerationPhase`
13. `PostReportGenerationPhase`
14. `ReportRenderingPhase`
15. `PostReportRenderingPhase`
16. `FinalizePhase`
17. `PostFinalizePhase`

### Rule Authoring Formats

Rules can be authored in three ways:

**1. Java code** -- Programmatic `AbstractRuleProvider` implementations with fluent API:
```java
@RuleMetadata(phase = MigrationRulesPhase.class)
public class MyRuleProvider extends AbstractRuleProvider {
    public Configuration getConfiguration(RuleLoaderContext ctx) {
        return ConfigurationBuilder.begin()
            .addRule()
            .when(JavaClass.references("javax.ejb.Stateless").at(ANNOTATION))
            .perform(Hint.titled("EJB Migration").withText("...").withEffort(3));
    }
}
```

**2. XML** -- `.windup.xml` / `.rhamt.xml` / `.mta.xml` files parsed by `XMLRuleProviderLoader` with element handlers for each condition/operation type.

**3. Groovy** -- `.windup.groovy` / `.rhamt.groovy` / `.mta.groovy` scripts evaluated via `GroovyWindupRuleProviderLoader` with a `GroovyShell`.

### config-xml -- XML Rule Parser

43 Java files implementing the XML rule format. Key types:

- **`XMLRuleProviderLoader`** -- Scans for `.windup.xml`/`.rhamt.xml`/`.mta.xml` files and parses them.
- **`ParserContext`** -- Maps `HandlerId` to `ElementHandler` instances.
- **`ElementHandler<T>`** -- SPI interface; each implementation handles one XML element type.
- **`@NamespaceElementHandler`** -- Registers handlers for namespace + element combinations.

Handlers cover the full rule structure: `RuleProviderHandler`, `RulesHandler`, `RuleHandler`, `WhenHandler`, `PerformHandler`, `OtherwiseHandler`, `AndHandler`, `OrHandler`, `NotHandler`, `QueryHandler`, `IterationHandler`, `LogHandler`, `WhereHandler`, `MatchesHandler`, metadata handlers, phase handlers, and label handlers.

### config-groovy -- Groovy Rule Loader

3 Java files. Creates a `GroovyShell` with composite classloader, sets up bindings with variables (`ruleLoaderContext`, `windupRuleProviderBuilders`), and evaluates scripts to extract `AbstractRuleProvider` instances. Extensible via the `GroovyConfigMethod` SPI.

---

## Java Analysis Pipeline

### java-ast -- Java Source Code Parsing

Uses **Eclipse JDT** to parse `.java` files into ASTs and extract every type reference.

**Key types:**

- **`ASTProcessor`** -- Single-file parser. Creates JDT `ASTParser` at JLS17 level, runs `ReferenceResolvingVisitor`.
- **`BatchASTProcessor`** -- Multi-file parser. Divides files into batches of `1000/availableProcessors`, uses `WindupASTParser` and `FileASTRequestor` for batch processing with thread pool.
- **`ReferenceResolvingVisitor`** -- Core AST visitor (~1,271 lines). Visits every significant node type, extracts `ClassReference` objects with qualified names, locations, resolution status. Handles: type declarations, imports, methods, constructors, fields, annotations, instanceof, throw/catch, return statements, enums.
- **`WindupASTParser`** -- Customized JDT parser fork. Adds the running VM's bootclasspath **last** instead of first, so older JDK APIs take precedence when analyzing JDK 8 apps on JDK 11+.
- **`WildcardImportResolver`** -- Pluggable SPI for resolving `import com.example.*` statements.

**Data model:**
- `ClassReference` -- qualifiedName, packageName, className, methodName, resolutionStatus, TypeReferenceLocation, lineNumber, column, length, source line text
- `TypeReferenceLocation` (enum, 18 values) -- IMPORT, TYPE, ENUM_CONSTANT, METHOD, INHERITANCE, CONSTRUCTOR_CALL, METHOD_CALL, METHOD_PARAMETER, ANNOTATION, RETURN_TYPE, INSTANCE_OF, THROWS_METHOD_DECLARATION, THROW_STATEMENT, CATCH_EXCEPTION_STATEMENT, FIELD_DECLARATION, VARIABLE_DECLARATION, IMPLEMENTS_TYPE, VARIABLE_INITIALIZER, TAGLIB_IMPORT
- `ResolutionStatus` (enum) -- RESOLVED, RECOVERED, UNRESOLVED, UNKNOWN
- `AnnotationClassReference` -- Extends `ClassReference` with annotation key-value pairs

### decompiler -- Bytecode Decompilation

Converts compiled `.class` files back to `.java` source for analysis of binary archives (WAR, JAR, EAR).

**Strategy pattern with two engines:**

- **Fernflower** (JetBrains IntelliJ decompiler) -- `windup-fernflower:1.0.0.20171018`. Configured with `MAX_PROCESSING_METHOD=30`, generic signatures, bytecode-to-source mapping. Primary decompiler.
- **Procyon** -- `procyon-core:0.5.33`. 60-second timeout per class to handle hangs. Memory-aware with cache flush every 50 classes.

**Key types:**

- **`Decompiler`** (interface) -- `decompileClassFiles()`, `decompileClassFile()`, `decompileArchive()`, `close()`
- **`AbstractDecompiler`** -- Shared base with thread pool management and inner-class grouping logic
- **`DecompilationListener`** -- Callback: `fileDecompiled()`, `decompilationFailed()`, `decompilationProcessComplete()`, `isCancelled()`
- **`DecompilationResult`** -- Tracks successes (map of class path to output) and failures

**Pipeline:** scan archive -> decompile `.class` to `.java` -> run `java-ast` analysis -> store in graph

---

## Rules Subsystem

### rules-base -- File Matching Primitives

Foundation for all file-based analysis. Provides:

- **`FileMapping`** -- Maps file name patterns (regex) to graph model types. Fluent: `FileMapping.from(".*\\.xml").to(XmlFileModel.class)`. Implements `PreRulesetEvaluation`.
- **`FileContent`** -- Matches file contents using streaming regex (Streamflyer). Creates `FileLocationModel` with line/column. Fluent: `FileContent.matches("pattern").inFileNamed("name")`.
- **`File`** -- Matches files by name only.
- **`StreamRegexMatcher`** -- Processes files in chunks rather than loading entirely into memory.

### rules-java -- Java Analysis Rules

The largest rules module (~120 API files). Provides the core `JavaClass` condition and all Java-specific analysis.

**Key conditions:**
- **`JavaClass`** -- Matches Java type references using regex. Supports filtering by `TypeReferenceLocation`, containing type (`inType`), source line (`matchesSource`), annotation values. Fluent: `JavaClass.references("javax.ejb.Stateless").at(TypeReferenceLocation.ANNOTATION)`.
- **`Dependency`** -- Searches dependencies by GAV across `IdentifiedArchiveModel` and `JarArchiveModel`. Fluent: `Dependency.withGroupId("org.example").andArtifactId("lib")`.

**Key rule providers (scanners):**
- `DiscoverMavenProjectsRuleProvider` -- Parses `pom.xml` to create `MavenProjectModel` nodes
- `AnalyzeJavaFilesRuleProvider` -- Triggers Java AST analysis
- `UnzipArchivesToOutputRuleProvider` / `DiscoverArchiveTypesRuleProvider` -- Archive extraction
- `DecompileClassesRuleProvider` -- Bytecode decompilation
- `DiscoverHardcodedIPAddressRuleProvider` -- Hardcoded IP detection
- `PackageNameMapping` -- Maps package prefixes to vendor names

**Report generators:** Application overview, dependency reports, dependency graphs, issue summaries, JSON summaries, compatible/unparsable/ignored file reports.

**Mavenize support:** `MavenizeRuleProvider` generates Maven project structure from non-Maven apps.

### rules-java-ee -- Java EE Technology Discovery

Discovers and analyzes Java EE / Jakarta EE technologies across multiple vendor platforms.

**Technology discovery:**
- EJBs -- `@Stateless`, `@Stateful`, `@MessageDriven` annotations (javax.ejb + jakarta.ejb), `ejb-jar.xml`
- JPA -- `@Entity`, `@Table` annotations, `persistence.xml` (Java EE, JCP, Jakarta namespaces)
- JAX-RS -- `@Path` annotations (javax.ws.rs / jakarta.ws.rs)
- JAX-WS -- `@WebService` annotations
- Hibernate -- XML config and mapping files
- Spring -- Bean XML definitions, JNDI references
- JSP/JSF -- Source file identification
- web.xml -- Deployment descriptors
- Remote EJB / RMI -- Remote service detection

**Vendor-specific descriptors:**
- **JBoss:** EJB XML, web XML, legacy EJB, jBPM process files
- **WebLogic:** EJB XML, web XML
- **WebSphere:** EJB bindings/extensions, web XML, WS bindings/extensions
- **Orion:** EJB XML, web XML

**Report generators:** EJB, Hibernate, JPA, remote services, server resources, Spring beans.

### rules-java-archives -- Archive Identification

Identifies JAR archives by SHA1 hash using a pre-built Lucene index from Nexus repository data. Known open-source libraries are marked so they can be skipped during analysis.

- **`ArchiveIdentificationService`** (interface) -- Returns Maven `Coordinate` for a SHA1 hash
- **`LuceneArchiveIdentificationService`** -- Lucene index lookup
- **`InMemoryArchiveIdentificationService`** -- In-memory map from `.archive-metadata.txt` files
- **`CompositeArchiveIdentificationService`** -- Chains multiple services
- **`ArchiveIdentificationGraphChangedListener`** -- Auto-triggers on `ArchiveModel` addition to compute hashes and identify

### rules-java-project -- Project Dependency Matching

Conditions and operations for GAV-based project dependency matching.

- **`Project`** -- Checks if any project depends on a given `Artifact`. Fluent: `Project.dependsOnArtifact(Artifact.withGroupId("org.hibernate").andArtifactId("hibernate-core"))`.
- **`Artifact`** -- GAV criteria with version range and dependency location filtering.
- **`LineItem`** -- Creates overview report messages attached to projects.

### rules-java-diva -- Transaction Analysis

Integrates the DIVA engine for deep static analysis using IBM WALA. Performs call-graph analysis to discover database transactions, REST API calls, and inter-service communication patterns. Opt-in via `EnableTransactionAnalysisOption`.

Analysis flow: construct WALA scope -> build class hierarchy and call graph -> run relevance analysis (JDBC, JPA, Spring Boot, Quarkus) -> identify entry points (Servlets, Spring controllers) -> perform transaction analysis -> resolve REST endpoints.

### rules-xml -- XML Analysis

XML file discovery, parsing, XPath matching, DTD/namespace detection, and XSLT transformation.

- **`XmlFile`** -- XPath matching, DTD matching, filename filtering, namespace declarations. Fluent: `XmlFile.matchesXpath("//jee:persistence-unit").namespace("jee", "http://...").inFile("persistence.xml")`.
- **`XSLTTransformation`** -- XSLT transformations with Saxon and Xalan support.
- **`DiscoverXmlFilesRuleProvider`** -- Registers mappings for `.xml`, `.xmi`, `.jsf`, `.xhtml`. Extracts root tags, DTD metadata, namespaces.

### rules-yaml -- YAML Discovery

Minimal module registering `.yml` and `.yaml` file type mappings.

### rules-tattletale -- Tattletale Integration

Integrates JBoss Tattletale for JAR dependency analysis and duplicate class detection. Generates an embedded Tattletale HTML report. Enabled by default for EAP targets; controllable via CLI options.

---

## Reporting and UI

### reporting -- FreeMarker HTML Reports

Core reporting engine producing HTML migration analysis reports.

**Template engine:** FreeMarker (`.ftl` templates in `reporting/impl/src/main/resources/reports/templates/`)

**Key templates:**
- `application_list.ftl` -- Application list/overview (index page)
- `migration-issues.ftl` -- Migration issues summary
- `source.ftl` -- Source code viewer with highlighted hints
- `techReport-punchCard.ftl` -- Technology punch card visualization
- `techReport-boxes.ftl` -- Technology boxes report
- `ruleprovidersummary.ftl` -- Rule execution summary
- `dependency_graph.ftl` -- Dependency graph visualization

**Key models:**
- `ReportModel` -- Base for all reports (template path, filename, hierarchy)
- `ApplicationReportModel` -- Application-specific reports
- `ClassificationModel` -- File classifications with effort points, issue category, quickfixes
- `InlineHintModel` -- Source-level hints at specific line numbers with title, text, links, quickfixes
- `TechnologyTagModel` -- Tags files with detected technology names/versions
- `IssueCategoryModel` -- Categories: mandatory, optional, potential, information, cloud-mandatory, cloud-optional

**Rule DSL builders:**
- `Classification.as("...")` -- Classify files
- `Hint.titled("...")` -- Add inline hints
- `TechnologyTag` -- Tag files with technologies
- `Link` -- Add reference links

**Export capabilities:**
- CSV export via `ExportCSVFileRuleProvider` (per-app CSVs + merged `AllIssues.csv` + `ApplicationFileTechnologies.csv`)
- ZIP export via `ExportZipReportRuleProvider`
- Graph formats: DOT, GEXF, GraphML, Graphlib

**FreeMarker extension SPI:** `WindupFreeMarkerMethod` and `WindupFreeMarkerTemplateDirective` for registering custom template methods/directives.

### reporting-data -- JSON Data Extraction

JSON data extraction layer for the PatternFly UI. Queries the graph and serializes data to JSON files using Jackson. Also generates `windup.js` embedding all JSON as `window[]` globals for the static SPA.

**Base class:** `AbstractApiRuleProvider` -- Serializes data to JSON in `api/` directory and to `windup.js`.

**Data rule providers:**

| Provider | Output | Description |
|----------|--------|-------------|
| `ApplicationsRuleProvider` | `applications.json` | App name, ID, tags, story points, incidents |
| `IssuesRuleProvider` | `issues.json` | Issues by severity with effort, links, affected files |
| `TechnologiesRuleProvider` | `technologies.json` | Technology usage matrix |
| `DependenciesRuleProvider` | `dependencies.json` | Maven/library dependencies with SHA1 |
| `FilesRuleProvider` | `files.json` + per-file | Source files with hints, tags, content |
| `ApplicationDetailsRuleProvider` | `application-details.json` | File tree, Maven info, messages |
| `ApplicationEJBsRuleProvider` | EJB data | EJB beans |
| `ApplicationJPAsRuleProvider` | JPA data | JPA entities, persistence units |
| `ApplicationHibernateRuleProvider` | Hibernate data | Hibernate entities, session factories |
| `ApplicationSpringBeansRuleProvider` | Spring data | Spring beans |
| `ApplicationRemoteServicesRuleProvider` | Remote services | JAX-RS, JAX-WS, EJB remote, RMI |
| `ApplicationServerResourcesRuleProvider` | Server resources | Datasources, JMS, JNDI |
| `RulesRuleProvider` | `rules.json` | Rule execution results |
| `LabelsRuleProvider` | Labels | Labels |
| `TagsRuleProvider` | Tags | Tags |

**Coordination:** `DataGatheringRuleProvider` waits for async extraction threads. `UIRuleProvider` extracts the compiled React app from `pf-windup-ui.zip` and copies it to output with `windup.js` and theming.

### pf-ui -- PatternFly React Frontend

Next-generation interactive frontend consuming JSON data from `reporting-data`.

**Technology stack:**
- React 17 + TypeScript
- PatternFly 4 (react-core, react-table, react-charts, react-code-editor)
- React Router DOM 6
- TanStack React Query 4
- Axios for HTTP
- Monaco Editor for source code display
- react-markdown + remark-gfm
- Built with react-app-rewired (CRA wrapper)
- Node.js 16.20.0 / npm 8.19.4 (via frontend-maven-plugin)

**Pages:**
- `/applications` -- Overview of analyzed applications
- `/issues` -- All migration issues with category filtering
- `/technologies` -- Technology usage summary
- `/dependencies` -- Dependency listing
- `/rules` -- Rule execution results

**Per-application detail views:** dashboard, issues, details (file tree), technologies, dependencies, ignored-files, ejb, jpa, hibernate, spring-beans, remote-services, server-resources, unparsable-files, transactions, hardcoded-ip-addresses, compatible-files, jbpm.

**Build/packaging:** `frontend-maven-plugin` installs Node, runs npm install/build. Maven assembly packages `build/` output into `pf-windup-ui.zip`, embedded as classpath resource in `reporting-data`.

### ui -- Forge UI Command Abstraction

CLI/Forge UI abstraction layer. Provides `WindupCommand` that integrates Windup execution with JBoss Forge's UI framework.

- **`WindupCommand`** -- Implements Forge `UICommand`. Dynamically builds UI form from all `ConfigurationOption` instances. On execution, creates `WindupConfiguration`, opens `GraphContext`, runs `WindupProcessor.execute()`, returns path to `index.html`.
- **`WindupUpdateRulesetCommand`** / **`WindupUpdateDistributionCommand`** -- Update commands.

### tooling -- IDE Integration API

Programmatic API for IDE plugins and external tools via Java RMI.

- **`ExecutionBuilder`** (RMI interface) -- Configure and run Windup remotely. Methods: `setWindupHome()`, `setInput()`, `setOutput()`, `execute()`, `getRuleProviderRegistry()`, `transform()`.
- **`ToolingRMIServer`** -- Registers `ExecutionBuilder` in RMI registry.
- **`ToolingModeRunner`** -- Direct execution (not RMI) with JSON progress output for VS Code / language server.
- **DTOs:** `Classification`, `Hint`, `Quickfix`, `ReportLink`, `IssueCategory`, `Link` -- Serializable transfer objects for results.

---

## CLI and Bootstrapping

### bootstrap -- CLI Entry Point

Contains `main()`, CLI argument parsing, Furnace container startup, and all CLI commands.

- **`Bootstrap`** -- `main()`. Parses system properties, sets up logging, creates Furnace, processes arguments into `Command` objects, executes in phases (PRE_CONFIGURATION -> CONFIGURATION -> POST_CONFIGURATION -> PRE_EXECUTION -> EXECUTION -> POST_EXECUTION).
- **`RunWindupCommand`** -- Main execution command. Parses `ConfigurationOption`s, prompts for target interactively if not batch mode, validates, configures, runs `WindupProcessor.execute()`.
- **Addon management:** `InstallAddonCommand`, `RemoveAddonCommand`, `ListAddonsCommand`, `AddAddonDirectoryCommand`
- **Information:** `DisplayHelpCommand`, `DisplayVersionCommand`, `ListSourceTechnologiesCommand`, `ListTargetTechnologiesCommand`
- **Utilities:** `GenerateCompletionDataCommand`, `DiscoverPackagesCommand`, `ServerModeCommand`, `ToolingModeCommand`

### bootstraps-themed -- Branded Variants

Produces branded distributions via Maven profiles:
- `bootstrap-windup` (default)
- `bootstrap-tackle` (profile `tackle`)
- `bootstrap-mtr` (profile `mtr`)
- `bootstrap-mta` (profile `mta`)

Each unpacks the base `windup-bootstrap` classes and overlays theme-specific resources (branding names, CLI versions, documentation URLs).

### server-provider-spi

Minimal SPI (1 file) defining `WindupServerProvider` interface with `getName()` and `runServer()` methods for pluggable server implementations.

---

## Build Infrastructure

### Build Configuration

- **Java version:** 11 (source/target)
- **Maven:** 3.8.8 via Maven Wrapper (`mvnw`)
- **Parent POM:** `org.jboss:jboss-parent:22`
- **Distribution:** Sonatype OSSRH (Maven Central staging)

### Key Maven Plugins

| Plugin | Purpose |
|--------|---------|
| `maven-compiler-plugin` 3.2 | Java 11, fork, 512m-128m memory, 4m stack |
| `maven-surefire-plugin` | `-Xms512m -Xmx2048m`, `reuseForks=false`, `forkCount=.5C` |
| `furnace-maven-plugin` | Furnace addon system: .dot files, addon installation |
| `maven-shade-plugin` 3.2.4 | Shading Kryo/Objenesis/Jackson in gremlin-shaded |
| `maven-jar-plugin` | `forge-addon` classifier JARs |
| `frontend-maven-plugin` | Node.js/npm for pf-ui build |
| `jacoco-maven-plugin` 0.7.9 | Code coverage (profile-activated) |
| `findbugs-maven-plugin` | Static analysis (profile-activated) |

### Build Profiles

| Profile | Purpose |
|---------|---------|
| `cleanup` | Removes artifacts from local repo |
| `javadocDist` | Aggregated Javadoc generation |
| `findbugs` | FindBugs static analysis |
| `jacoco` | JaCoCo code coverage |
| `surefire-jdk11` | JDK 11 module system config |
| `surefire-jdk17` | JDK 17 `--add-opens` config |

### Forks

- **`forks/jdt`** (`windup-eclipse-jdt`) -- Repackaged `org.eclipse.jdt.core:3.29.0` to avoid classpath conflicts.
- **`forks/gremlin-shaded`** -- Shaded TinkerPop dependencies (Kryo, Objenesis, Jackson relocated under `org.apache.tinkerpop.shaded.*`).

### CI/CD (GitHub Actions)

| Workflow | Trigger | Description |
|----------|---------|-------------|
| `pr_build_jdk11.yml` | PR + push to master | Main build + unit tests (JDK 11+17 matrix) + integration tests |
| `pr_build_jdk11_dependents.yml` | PR to master | Tests downstream projects (windup-rulesets, maven-plugin, quickstarts) |
| `pr_build_ui.yml` | PR + push to master | Node.js build of pf-ui |
| `backport.yml` | Labeled/closed PRs | Auto-backport via `sqren/backport-github-action` |

The main CI pipeline has 4 jobs in a dependency chain:
1. `windup-build` -- Full build, skip tests (JDK 11)
2. `windup-build-with-unit-tests-1` -- Matrix JDK 11+17, excludes slow test modules
3. `windup-build-with-unit-tests-2` -- Specifically tests config, rules-java, rules-xml
4. `windup-tests` -- Integration tests

### Release Process

`build/release_windup.sh` automates releases across three repos (windup, windup-rulesets, windup-distribution) using `release:prepare-with-pom` and `release:perform` with GPG signing.

---

## Testing

### Integration Tests (`tests/`)

End-to-end tests using **JBoss Furnace/Arquillian**. Tests run inside a Furnace container with full Windup addon stack deployed.

**Architecture tests** (55+ classes in `tests/src/test/java/.../application/`):
- Each extends `WindupArchitectureTest`, uses `@RunWith(Arquillian.class)` with `@AddonDependencies`
- Creates `WindupConfiguration`, runs `WindupProcessor.execute()`, validates graph and reports
- Examples: `WindupArchitectureJEEExampleTest`, `WindupArchitectureMediumBinaryModeTest`, `WindupArchitectureSourceModeTest`, `WindupArchitectureHibernateTest`, `WindupArchitectureSpringSmallTest`, `WindupArchitectureDependencyTest`

**Bootstrap tests** (20+ classes in `.../bootstrap/`):
- Extends `AbstractBootstrapTest`, captures stdout/stderr
- Calls `Bootstrap.main()` with `--batchMode`
- Tests: help, version, addon management, package discovery, run command, tooling mode

### Test Utilities (`test-util/`)

Selenium/HtmlUnit-based report validators:
- `TestJavaApplicationOverviewUtil` -- Application overview reports
- `TestApplicationListUtil` -- Application list reports
- `TestEJBReportUtil`, `TestHibernateReportUtil`, `TestJPAReportUtil`, `TestSpringBeanReportUtil` -- Technology reports
- `TestMigrationIssuesReportUtil` -- Migration issues reports
- `TestDependencyReportUtil` / `TestDependencyGraphReportUtil` -- Dependency reports
- `TestTechReportUtil` -- Technology report (bubbles, boxes, points)
- `TestChromeDriverReportUtil` -- Chrome WebDriver variant

Dependencies include `selenium-chrome-driver:4.8.1` and `htmlunit-driver:4.8.0`.

### Test Data (`test-files/`)

Application artifacts for testing:
- **Archives:** `jee-example-app-1.0.0.ear`, `hibernate-tutorial-web-3.3.2.GA.war`, `spring-small-example.war`, `Windup1x-javaee-example.war`
- **Source projects:** `src_example/`, `seam-booking-5.2/`
- **Specific scenarios:** `ejb/` (JBoss/Orion/WebLogic/WebSphere configs), `jsptest/`, `soa/`, `spring-hibernate-jndi-test/`, `duplicate/`, `techReport/`

---

## Key Technologies and Dependencies

| Technology | Version | Purpose |
|------------|---------|---------|
| JanusGraph | 0.6.3 | Graph database |
| Apache TinkerPop | 3.5.5 | Graph traversal framework |
| Ferma | (via BOM) | Graph-to-Java object mapping |
| ByteBuddy | (via Ferma) | Runtime proxy generation |
| JBoss Forge Furnace | 2.29.1.Final | Modular addon container |
| JBoss Forge | 3.10.0.Final | Framework/addons |
| OCPSoft Rewrite | (via Forge) | Rule engine foundation |
| Eclipse JDT | 3.29.0 | Java AST parsing |
| Fernflower | 1.0.0.20171018 | Java decompilation |
| Procyon | 0.5.33 | Java decompilation (alternative) |
| FreeMarker | 2.3.31 | HTML report templating |
| Lucene | 7.1.0 | Archive identification indexing |
| Groovy | 2.4.21 | Groovy rule scripting |
| IBM WALA | (via DIVA) | Call-graph analysis |
| Saxon-HE | (via rules-xml) | XSLT processing |
| React | 17.0.2 | Frontend SPA |
| PatternFly | 4.224.2 | UI design system |
| TypeScript | 4.8+ | Frontend type safety |
| Jackson | 2.13-2.15 | JSON serialization |
| Guava | 32.0.1-jre | Utility library |
| Apache Commons | various | IO, Lang, Collections, Compress |
| Selenium | 4.8.1 | Report testing |
| JUnit | 4.13.1 | Testing framework |
| Arquillian | (via Furnace) | Integration testing |

---

## Execution Flow

```
1. bootstrap: Bootstrap.main()
   |-- Parse CLI arguments
   |-- Start Furnace container
   |-- RunWindupCommand
   |
2. exec: WindupProcessor.execute(WindupConfiguration)
   |-- Validate configuration
   |-- Create GraphContext (JanusGraph + BerkeleyJE)
   |-- Store WindupConfigurationModel in graph
   |
3. config: RuleLoader.loadConfiguration()
   |-- Discover RuleProviderLoader implementations
   |   |-- XMLRuleProviderLoader (parse .windup.xml files)
   |   |-- GroovyWindupRuleProviderLoader (evaluate .windup.groovy)
   |   |-- Java RuleProviders (from Furnace addons)
   |-- Sort by phase + dependencies (RuleProviderSorter)
   |-- Build RuleSubset
   |
4. exec/config: RuleSubset.perform()
   |
   |-- Phase: InitializationPhase
   |   |-- Load archive identification data
   |   |-- Load ignore patterns
   |
   |-- Phase: ArchiveExtractionPhase
   |   |-- Unzip WAR/EAR/JAR archives
   |
   |-- Phase: ClassifyFileTypesPhase
   |   |-- Map file extensions to graph model types
   |   |-- Identify archive types
   |
   |-- Phase: DiscoverProjectStructurePhase
   |   |-- Parse pom.xml -> MavenProjectModel
   |   |-- Discover parent/child relationships
   |
   |-- Phase: DecompilationPhase
   |   |-- Decompile .class files (Fernflower/Procyon)
   |
   |-- Phase: InitialAnalysisPhase
   |   |-- Parse Java source (Eclipse JDT -> ClassReference)
   |   |-- Parse XML files (extract DTD, namespaces, root tags)
   |   |-- Index Java source files
   |
   |-- Phase: MigrationRulesPhase
   |   |-- Evaluate all migration rules
   |   |-- Match JavaClass conditions against type references
   |   |-- Match XmlFile conditions via XPath
   |   |-- Match Project/Dependency conditions
   |   |-- Create ClassificationModel, InlineHintModel, TechnologyTagModel
   |   |-- Discover EJBs, JPA, JAX-RS, Hibernate, Spring, etc.
   |
   |-- Phase: ReportGenerationPhase
   |   |-- Create report models
   |   |-- Generate overview, migration issues, technology reports
   |
   |-- Phase: ReportRenderingPhase
   |   |-- Render FreeMarker templates to HTML
   |   |-- Export CSV/ZIP if configured
   |   |-- Extract JSON data for PatternFly UI
   |   |-- Deploy React SPA
   |
   |-- Phase: FinalizePhase
       |-- Cleanup, statistics
```

---

## Data Flow Diagram

```
 Input Application(s)                     Rule Definitions
 (WAR, EAR, JAR, source)          (.windup.xml, .groovy, Java)
         |                                      |
         v                                      v
 +-----------------+                   +-----------------+
 | Archive         |                   | Rule Loading    |
 | Extraction &    |                   | & Sorting       |
 | File Discovery  |                   | (by phase)      |
 +--------+--------+                   +--------+--------+
          |                                     |
          v                                     v
 +-----------------+                   +-----------------+
 | Decompilation   |                   | Rule Evaluation |
 | (Fernflower/    |<----------------->| (RuleSubset     |
 |  Procyon)       |  graph read/write |  .perform())    |
 +--------+--------+                   +--------+--------+
          |                                     |
          v                                     v
 +-----------------+              +---------------------------+
 | Java AST        |              | Graph Database            |
 | Analysis        |              | (JanusGraph/BerkeleyJE)   |
 | (Eclipse JDT)   |------------->| Models: Files, Projects,  |
 +-----------------+              | Dependencies, Types,      |
                                  | Classifications, Hints,   |
                                  | Technologies, Reports     |
                                  +------------+--------------+
                                               |
                          +--------------------+--------------------+
                          |                    |                    |
                          v                    v                    v
                 +--------------+    +-----------------+   +---------------+
                 | FreeMarker   |    | JSON Data       |   | CSV Export    |
                 | HTML Reports |    | Extraction      |   | (AllIssues,  |
                 | (legacy)     |    | (reporting-data)|   |  per-app)    |
                 +--------------+    +--------+--------+   +---------------+
                                              |
                                              v
                                    +-----------------+
                                    | PatternFly UI   |
                                    | (React SPA)     |
                                    | index.html      |
                                    +-----------------+
```

---

*Document generated from source code analysis of windup 6.4.0-SNAPSHOT, August 2026.*

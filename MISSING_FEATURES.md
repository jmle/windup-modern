# Missing Features: Go Provider vs Java Provider

This document catalogs features present in the Go-based Konveyor Java external provider
(`analyzer-lsp/external-providers/java-external-provider`) that are not yet implemented in
`windup-grpc`. These features are required for production parity.

Reference code in `windup-core/` and `windup-java/` (excluded from the build) may be useful
when implementing some of these.

---

## 1. Decompilation (FernFlower)

**Priority: High**

Decompiles `.class` files from JARs/WARs/EARs into `.java` source so they can be analyzed
for symbol references in dependencies.

**Go implementation:**
- `dependency/decompile.go` -- worker pool (10 parallel workers), dispatches jobs via channel
- `dependency/jar.go`, `dependency/war.go`, `dependency/ear.go` -- per-archive-type handlers
- `dependency/class_decompile_job.go` -- single class decompilation job
- `dependency/explosion.go` -- archive extraction using `jar -xvf`
- Runs `java -jar fernflower.jar -mpm=30 <input> <output>` per artifact
- Decompiled sources stored as `*-sources.jar` alongside binary JARs
- Supports recursive extraction: EARs contain WARs/JARs, WARs contain JARs in WEB-INF/lib

**Trigger:** During `Init()` when dependencies lack source JARs, and on-demand via
`GetSourceFileLocation()` when a decompiled source is not yet available.

**Config:** `fernFlowerPath` provider setting (default `/bin/fernflower.jar`).

**Reference code in this repo:**
- `windup-java/src/main/java/org/jboss/windup/java/decompiler/DecompilerService.java` -- decompiler interface
- `windup-java/src/main/java/org/jboss/windup/java/decompiler/FallbackDecompiler.java` -- javap fallback
- `windup-java/src/main/java/org/jboss/windup/java/decompiler/ProcyonDecompiler.java` -- Procyon integration
- `windup-core/src/main/java/org/jboss/windup/engine/archive/ArchiveExtractionProvider.java` -- ZIP/JAR/WAR/EAR extraction

---

## 2. Maven SHA Index Lookup

**Priority: High**

Identifies unknown JARs by computing their SHA1 hash and performing a binary search
against a pre-built sorted text index file (`maven-index.txt`). Maps SHA1 hashes to
Maven coordinates (`groupId:artifactId:packaging:classifier:version`).

**Go implementation:**
- `dependency/artifact.go` -- `constructArtifactFromSHA()`, `search()`, `searchIndex()` (binary search)
- Falls back to `constructArtifactFromPom()` (reads `META-INF/maven/*/*/pom.properties` inside JAR)
- Last resort: `ToFilePathDependency()` infers coordinates from file path structure

**Trigger:** During decompilation and binary dependency walking.

**Config:** `mavenIndexPath` provider setting.

**Reference code in this repo:**
- `windup-core/src/main/java/org/jboss/windup/engine/discovery/HashCalculator.java` -- SHA-1/MD5 computation
- `windup-core/src/main/java/org/jboss/windup/engine/archive/ArchiveIdentifier.java` -- manifest-based identification

---

## 3. Build Tool Abstraction (Maven + Gradle + Binary)

**Priority: High**

Unified `BuildTool` interface with three implementations that handle dependency resolution,
source download, and decompile fallback.

**Go implementation:**
- `bldtool/tool.go` -- `BuildTool` interface, `GetBuildTool()` detection (Gradle > Binary > Maven)
- `bldtool/maven.go` -- Maven: detects `pom.xml`, runs `mvn dependency:tree`
- `bldtool/gradle.go` -- Gradle: detects `build.gradle`, runs `gradlew dependencies`, subproject support
- `bldtool/maven_binary.go` -- Binary: detects `.jar/.war/.ear` extension, always resolves

**Key methods per BuildTool:**
- `GetDependencies()` -- runs build tool commands, parses output into dependency DAG
- `GetResolver()` -- creates resolver for source download + decompilation
- `GetSourceFileLocation()` -- resolves `konveyor-jdt://` URIs to actual file paths
- `GetLocalRepoPath()` -- Maven `.m2/repository` or Gradle `caches/modules-2/files-2.1`

**Current state in windup-grpc:** `DependencyParser.java` does simple XML parsing of `pom.xml`
and regex parsing of `build.gradle`. Does not run build tool commands or resolve transitive
dependencies.

---

## 4. Dependency Resolution (Source Download + Decompile Fallback)

**Priority: High**

Downloads source JARs for all dependencies. Decompiles those without sources.

**Go implementation:**
- `dependency/maven_resolver.go` -- runs `mvn de.qaware.maven:go-offline-maven-plugin:resolve-dependencies -DdownloadSources`
- `dependency/gradle_resolver.go` -- injects custom `konveyorDownloadSources` Gradle task, handles Gradle 9+ compatibility
- `dependency/binary_resolver.go` -- decompiles entire binary into synthetic `java-project/`
- `gradletasks/tasks.go` -- embedded Gradle task definitions for source resolution

**Why it matters:** Without this, the provider cannot analyze code in dependencies. Rules that
match patterns in third-party libraries (e.g., Spring framework classes) would miss incidents
where the application extends or overrides dependency code.

---

## 5. WAR/EAR Archive Handling

**Priority: Medium**

Full support for exploding and analyzing WAR and EAR enterprise archives.

**Go implementation:**
- `dependency/war.go` -- explodes WARs, decompiles `WEB-INF/classes` into `src/main/java`,
  treats `WEB-INF/lib/*.jar` as dependencies, copies static assets to `src/main/webapp`
- `dependency/ear.go` -- explodes EARs, recursively processes contained JARs and WARs
- `dependency/explosion.go` -- uses `jar -xvf` to extract archives

**Reference code in this repo:**
- `windup-core/src/main/java/org/jboss/windup/engine/archive/ArchiveExtractionProvider.java`
- `windup-core/src/main/java/org/jboss/windup/model/ArchiveType.java` -- JAR, WAR, EAR, RAR, SAR, ZIP

---

## 6. JDT Class File URI Resolution

**Priority: Medium**

Converts `konveyor-jdt://` URIs (returned by JDTLS for symbols found in dependencies) back
to actual `.java` file paths on disk. Handles `source-range` query parameters and inner class
markers (`$`).

**Go implementation:**
- `filter.go` `getURI()` (lines 147-240)
- Delegates to `BuildTool.GetSourceFileLocation()` which may trigger on-demand `jar xf`

**Note:** This is only relevant if the Java provider delegates to JDTLS. If windup-grpc
continues to do its own AST parsing, this URI scheme is not needed -- but the provider must
still be able to resolve incidents found in decompiled dependency sources back to meaningful
file paths.

---

## 7. Dependency Labeling

**Priority: Medium**

Labels each dependency as `konveyor.io/dep-source=open-source` or `internal`, and adds
`konveyor.io/language=java`. Supports `konveyor.io/exclude` for filtering.

**Go implementation:**
- `dependency/labels/labels.go` -- `Labeler` interface, `AddLabels()` method
- Uses a file of regex patterns (`depOpenSourceLabelsFile`) to classify dependencies

**Config:** `depOpenSourceLabelsFile`, `excludePackages`.

**Why it matters:** The engine uses these labels to scope dependency analysis. Without them,
the engine cannot distinguish open-source from internal dependencies.

---

## 8. Maven Remote Artifact Download

**Priority: Low**

If `config.Location` starts with `mvn://`, downloads the artifact from a Maven repository
before analysis begins.

**Go implementation:**
- `bldtool/maven_downloader.go` -- `Download()` method
- Format: `mvn://<group>:<artifact>:<version>:<classifier>@<path>`

---

## 9. Maven Settings Generation

**Priority: Low**

Generates/updates `~/.analyze/globalSettings.xml` with custom local repository path and
HTTP/HTTPS proxy configuration.

**Go implementation:**
- `maven_settings.go` -- `BuildSettingsFile()`

**Config:** `mavenCacheDir`, `proxy` in init config.

**Why it matters:** Required for containerized/enterprise deployments with HTTP proxies or
custom Maven cache directories.

---

## 10. Bytecode Scanning (.class File Analysis)

**Priority: Medium**

Reads `.class` files and extracts type references from the constant pool (class refs, field
refs, method refs) without full decompilation. Faster than decompilation for initial scanning.

**Go implementation:** Not directly in the Go provider (JDTLS handles this). But useful as a
complement/alternative to decompilation for quick dependency scanning.

**Reference code in this repo:**
- `windup-java/src/main/java/org/jboss/windup/java/scan/ClassFileScanner.java`
- `windup-java/src/main/java/org/jboss/windup/java/scan/ClassFileScanResult.java`

---

## 11. Dependency Caching

**Priority: Low**

Caches dependency resolution results keyed by SHA256 hash of the build file (`pom.xml` or
`build.gradle`). Avoids re-running expensive Maven/Gradle commands on unchanged projects.

**Go implementation:**
- `bldtool/dep_cache.go`

---

## 12. Filepath-Scoped Evaluation

**Priority: Medium**

Evaluation can be scoped to specific file paths via `includedPaths` in provider config and
per-condition `filepaths` constraints. The Go provider runs a parallel file search to
determine which files to include.

**Go implementation:**
- `service_client.go` `GetAllSymbols()` lines 158-298
- Provider-level `includedPaths`, rule-scope included/excluded paths, condition-level `filepaths`

**Current state in windup-grpc:** Not implemented. All files in the workspace are included
in query results.

---

## 13. Rule Query Timeout

**Priority: Low**

Configurable timeout for individual rule queries to prevent hangs on pathological patterns.

**Go implementation:** `ruleQueryTimeout` provider setting (default varies, e.g., "15m").

---

## 14. Gradle-Specific Features

**Priority: Medium** (if Gradle projects are in scope)

- Subproject discovery via `gradlew projects`
- Gradle wrapper enforcement (never uses system Gradle)
- Java version compatibility (Gradle <= 8.14 uses `JAVA8_HOME`)
- Gradle 9+ support (removal of `--build-file`, `--no-configuration-cache`)
- Pre-resolution via custom `konveyorResolveDependencies` task
- Custom task injection into `build.gradle`

**Go implementation:** `bldtool/gradle.go`, `dependency/gradle_resolver.go`, `gradletasks/tasks.go`

---

## Summary by Priority

| Priority | Features |
|----------|----------|
| High     | 1. Decompilation, 2. Maven SHA Index, 3. Build Tool Abstraction, 4. Dependency Resolution |
| Medium   | 5. WAR/EAR Archives, 6. URI Resolution, 7. Dependency Labeling, 10. Bytecode Scanning, 12. Filepath Scoping, 14. Gradle Features |
| Low      | 8. Remote Artifact Download, 9. Maven Settings, 11. Dependency Caching, 13. Query Timeout |

## Retained Reference Modules

The following modules are excluded from the Maven build but retained on disk as reference
for implementing the features above:

- **windup-core/** -- Archive extraction, manifest identification, SHA hashing, archive/file models
- **windup-java/** -- Decompiler integration (FernFlower/Procyon/javap), bytecode `.class` scanning, Maven POM parsing

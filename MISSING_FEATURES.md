# Missing Features: Go Provider vs Java Provider

This document catalogs features present in the Go-based Konveyor Java external provider
(`analyzer-lsp/external-providers/java-external-provider`) that are not yet implemented in
`windup-grpc`. These features are required for production parity.

Reference code in `windup-core/` and `windup-java/` (excluded from the build) may be useful
when implementing some of these.

---

## ~~1. Decompilation (Vineflower)~~ DONE

**Status: Implemented**

Embedded Vineflower 1.10.1 as a library dependency. Uses `Fernflower` class directly with
custom `DirectoryResultSaver` (implements `IResultSaver`) to write `.java` files to disk.
Parallel decompilation via `ExecutorService` worker pool.

**Implementation:**
- `windup-grpc/.../decompiler/VineflowerDecompiler.java` -- core decompiler with worker pool
- `windup-grpc/.../decompiler/ArchiveHandler.java` -- JAR/WAR/EAR handlers with recursive extraction
- `windup-grpc/.../decompiler/DecompilerService.java` -- interface
- `windup-grpc/.../decompiler/DecompileResult.java` -- result record

---

## ~~2. Maven SHA Index Lookup~~ DONE

**Status: Implemented**

Binary search over sorted text file (`maven-index.txt`) mapping SHA1 → Maven coordinates.
Uses `RandomAccessFile` for random access. Falls back gracefully when index file not found.

**Implementation:**
- `windup-grpc/.../buildtool/MavenShaIndex.java` -- binary search, SHA1 computation, coordinate parsing

---

## ~~3. Build Tool Abstraction (Maven + Gradle + Binary)~~ DONE

**Status: Implemented**

Unified `BuildTool` interface with Maven, Gradle, and Binary implementations. Detection
priority: Gradle > Maven > Binary (matching Go provider). Runs actual build tool commands
for transitive dependency resolution.

**Implementation:**
- `windup-grpc/.../buildtool/BuildTool.java` -- interface with `Type` enum and `ResolvedDependency` record
- `windup-grpc/.../buildtool/BuildToolDetector.java` -- detection logic
- `windup-grpc/.../buildtool/MavenBuildTool.java` -- runs `mvn dependency:tree`, parses output
- `windup-grpc/.../buildtool/GradleBuildTool.java` -- runs `gradlew dependencies`, wrapper support
- `windup-grpc/.../buildtool/BinaryBuildTool.java` -- walks directory for archives

---

## ~~4. Dependency Resolution (Source Download + Decompile Fallback)~~ DONE

**Status: Implemented**

Full pipeline: detect build tool → resolve deps → download source JARs (`mvn dependency:sources`)
→ decompile missing sources via Vineflower → index all dependency sources.
`IsDependencyIncident` flag set on incidents from dependency files.

**Implementation:**
- `windup-grpc/.../buildtool/DependencyResolver.java` -- orchestrates source extraction + decompilation
- `windup-grpc/.../buildtool/DependencyLabeler.java` -- open-source vs internal classification
- `windup-grpc/.../index/SymbolIndex.java` -- `indexDependencyDirectory()` and `isDependencyFile()`
- `windup-grpc/.../WorkspaceContext.java` -- wired into `index()` flow

---

## ~~5. WAR/EAR Archive Handling~~ DONE

**Status: Implemented**

JAR/WAR/EAR handlers with recursive extraction. WAR: decompiles WEB-INF/classes, collects
WEB-INF/lib/*.jar as dependencies. EAR: recursively processes contained JARs/WARs.

**Implementation:**
- `windup-grpc/.../decompiler/ArchiveHandler.java` -- `handleJar()`, `handleWar()`, `handleEar()`, `explode()`

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

## ~~7. Dependency Labeling~~ DONE

**Status: Implemented**

Labels dependencies as `konveyor.io/dep-source=open-source|internal`, adds
`konveyor.io/language=java`, supports `konveyor.io/exclude` for filtering.
Configurable via regex patterns file and exclude packages list.

**Implementation:**
- `windup-grpc/.../buildtool/DependencyLabeler.java` -- classification logic, config loading
- `windup-grpc/.../WorkspaceContext.java` -- wired into `getDependenciesFromBuildTool()` response

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

## Summary

| Status | Features |
|--------|----------|
| **Done**    | ~~1. Decompilation~~, ~~2. Maven SHA Index~~, ~~3. Build Tool Abstraction~~, ~~4. Dependency Resolution~~, ~~5. WAR/EAR Archives~~, ~~7. Dependency Labeling~~ |
| **Pending** | 6. URI Resolution (Medium), 10. Bytecode Scanning (Medium), 12. Filepath Scoping (Medium), 14. Gradle Features (Medium) |
| **Low**     | 8. Remote Artifact Download, 9. Maven Settings, 11. Dependency Caching, 13. Query Timeout |

## Retained Reference Modules

The following modules are excluded from the Maven build but retained on disk as reference
for implementing the remaining features:

- **windup-core/** -- Archive extraction, manifest identification, SHA hashing, archive/file models
- **windup-java/** -- Decompiler integration (FernFlower/Procyon/javap), bytecode `.class` scanning, Maven POM parsing

# Missing Features: Go Provider vs Java Provider

This document catalogs features present in the Go-based Konveyor Java external provider
(`analyzer-lsp/external-providers/java-external-provider`) that are not yet implemented in
`java-analyzer-provider`. These features are required for production parity.

Previously completed features (decompilation, Maven SHA index, build tool abstraction,
dependency resolution, WAR/EAR archive handling, dependency labeling, `mvn://` artifact
download, `includedPaths` config, `filepaths` condition parameter, `depOpenSourceLabelsFile`
config wiring, Maven settings generation + proxy support, `GetDependenciesDAG`) have been
removed from this list.

---

## 1. `depLabelSelector` / Open-Source Library Scope Filtering

**Priority: Medium**

The Go provider reads a label selector from the condition context and uses it to decide
whether dependency-sourced symbols should be included in query results. It passes
`includeOpenSourceLibraries` to the JDTLS rule query command. This allows rules to match
only in application code, excluding third-party library incidents.

**Go implementation:**
- `service_client.go` — reads `depLabelSelector` from condition context
- Calls `CanRestrictSelector()` to determine if open-source libraries should be excluded
- Passes `includeOpenSourceLibraries` boolean to JDTLS

**Current state:** Not implemented. All indexed symbols (application + dependency) are always
included in query results regardless of label selectors.

**Effort:** Medium — need to parse the selector, check dependency labels, and filter
`SymbolIndex` query results accordingly.

---

## 3. File Encoding Support

**Priority: Medium**

The Go provider reads a file encoding setting from config and uses it when extracting code
snippets. The Java provider always reads files as UTF-8.

**Go implementation:**
- `provider.go` Init — `provider.GetEncodingFromConfig(config)`
- `snipper.go` — uses encoding when reading source files

**Current state:** `CodeSnipService` uses `Files.readAllLines(path)` which defaults to UTF-8.

**Effort:** Low — read encoding from config, pass `Charset` to file reading operations.

---

## 3. Binary Artifact Identification

**Priority: Medium**

The Go provider's binary build tool is significantly more sophisticated: it discovers embedded
JARs inside WARs/EARs, creates synthetic Maven projects for resolution, and identifies
artifacts via `pom.properties` files found inside JARs.

**Go implementation:**
- `bldtool/maven_binary.go` — full binary analysis pipeline
- `dependency/artifact.go` — JAR identification via SHA, Maven index, pom.properties
- `dependency/jar.go`, `jar_explode.go` — JAR analysis and extraction

**Current state:** `BinaryBuildTool` assigns synthetic coordinates
(`io.konveyor.embededdep` / `0.0.0-SNAPSHOT`) to all discovered archives. `MavenShaIndex`
exists but is not used by `BinaryBuildTool` for identification.

**Effort:** Medium — wire `MavenShaIndex` into `BinaryBuildTool`, add `pom.properties`
extraction from JARs, improve embedded JAR discovery.

---

## 4. Multi-Module Maven Support

**Priority: Medium**

The Go provider handles Maven multi-module projects by discovering and merging dependency
trees from submodules.

**Go implementation:**
- `bldtool/maven.go` — discovers `<modules>` in parent pom, runs `dependency:tree` per module
- Merges results, handles inter-module dependencies

**Current state:** `MavenBuildTool` processes only the root `pom.xml`. Multi-module projects
will only report dependencies declared in the parent pom, missing module-specific dependencies.

**Effort:** Medium.

---

## 5. Dependency Caching

**Priority: Low**

Caches dependency resolution results keyed by SHA256 hash of the build file (`pom.xml` or
`build.gradle`). Avoids re-running expensive Maven/Gradle commands on unchanged projects.

**Go implementation:**
- `bldtool/dep_cache.go` — hash-based cache with file-system persistence

**Current state:** Not implemented. Dependencies are re-resolved on every `Init` call.

**Effort:** Low.

---

## 6. Gradle-Specific Features

**Priority: Low** (Maven dominates Konveyor's target workloads)

The Go provider has extensive Gradle support beyond what the Java provider implements:

- Custom Gradle task files (`gradletasks/tasks.go`) for better dependency extraction
- Gradle wrapper version detection and Java version compatibility
- Source download for Gradle dependencies via custom tasks
- Subproject discovery via `gradlew projects`
- Gradle 9+ support (removal of deprecated flags)

**Go implementation:**
- `bldtool/gradle.go` (555+ lines)
- `dependency/gradle_resolver.go`
- `gradletasks/tasks.go` — embedded `.gradle` scripts

**Current state:** `GradleBuildTool` runs `gradle dependencies --configuration compileClasspath`
and parses output. No custom tasks, no source download, no subproject handling.

**Effort:** Medium.

---

## 7. `NotifyFileChanges`

**Priority: Low**

The Go provider forwards file change notifications to service clients for incremental
re-indexing. Matters for IDE-like incremental workflows but not for batch analysis runs.

**Go implementation:**
- `provider.go` — delegates to `provider.FullNotifyFileChangesResponse`

**Current state:** `JavaProviderService.notifyFileChanges()` returns an empty response.

**Effort:** Low — re-index changed files in `SymbolIndex`.

---

## Not Gaps (Architectural Differences or Dead Code)

| Go Provider Feature | Why not a gap |
|---|---|
| JDT Class File URI Resolution (`konveyor-jdt://`) | JDTLS-specific; Java provider uses AST directly |
| `ruleQueryTimeout` | Only relevant to JDTLS approach |
| JDTLS process management / `$/progress` tracking | Architectural difference — Java provider embeds JDT Core |
| `excludePackages` in evaluation flow | Dead code in Go provider (defined but never called) |
| Bytecode scanning (.class analysis) | Not in Go provider either (JDTLS handles internally) |

---

## Summary

| Priority | Features |
|----------|----------|
| **Medium** | 1. `depLabelSelector` filtering, 2. File encoding, 3. Binary artifact ID, 4. Multi-module Maven |
| **Low** | 5. Dependency caching, 6. Gradle features, 7. `NotifyFileChanges` |

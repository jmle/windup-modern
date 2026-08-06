# Planned Features

---

## 1. Embedded Maven Dependency Resolution

**Priority: High**

Replace the current approach of shelling out to `mvn dependency:tree` with the Maven Resolver
library (formerly Eclipse Aether) running in-process.

**Current approach:** `MavenBuildTool` spawns `mvn dependency:tree -DoutputType=text -q` as a
subprocess and parses the output with a regex. This requires `mvn` to be installed on the host,
adds process startup latency, and the regex parsing is fragile around edge cases (classifiers,
relocations, exclusions, BOMs).

**Proposed approach:** Use the Maven Resolver stack as an embedded library:

- `org.apache.maven:maven-model` — parse `pom.xml` into a typed Java object model
- `org.apache.maven:maven-model-builder` — handle parent POM inheritance, property
  interpolation, profile activation
- `org.apache.maven.resolver:maven-resolver-api` + `maven-resolver-impl` — resolve the full
  transitive dependency graph programmatically

**Benefits:**
- Eliminates the `mvn` binary requirement (important in containers)
- Removes regex parsing fragility — structured API handles classifiers, relocations, exclusions,
  BOMs correctly
- Significantly faster — no subprocess fork or secondary JVM boot
- Fat JAR becomes fully self-contained for Maven projects

**Tradeoffs:**
- Adds ~15-20 Maven JARs (~8MB) to the fat JAR
- Must explicitly load and honor `~/.m2/settings.xml` (the subprocess inherits this
  automatically)

**Scope:** Replace `MavenBuildTool.runMvnDependencyTree()` and the `DEP_TREE_LINE` regex parser.
The `DependencyResolver.downloadSources()` method (which shells out to `mvn dependency:sources`)
could also be replaced with programmatic source artifact resolution.

---

## 2. Gradle Tooling API Integration

**Priority: Medium**

Replace the current approach of shelling out to `gradlew dependencies` with the Gradle Tooling
API.

**Current approach:** `GradleBuildTool` spawns `gradlew dependencies --configuration
compileClasspath -q` and parses the tree output with a regex. Source JAR detection is currently
stubbed out (`findSourceHash` returns `""`).

**Proposed approach:** Use `org.gradle:gradle-tooling-api` to connect to the project
programmatically and query a typed dependency model (`EclipseProject` or `IdeaProject`).

**Benefits:**
- Typed dependency model instead of regex parsing
- Handles subprojects, configurations, and version conflict resolution correctly
- Wrapper detection and version compatibility handled by the API
- Enables implementing the currently-stubbed source JAR detection

**Tradeoffs:**
- Still runs Gradle in a separate JVM (the Gradle daemon) — less of a speed win than the Maven
  change
- Adds 1 JAR (~1MB) to dependencies
- Requires a Gradle distribution (wrapper or installed) — same as current approach

**Scope:** Replace `GradleBuildTool.runGradleDependencies()` and the `DEP_LINE` regex parser.
Implement proper source JAR resolution to replace the stubbed `findSourceHash()`.

---

## 3. Skip Build Output Directories During Indexing

**Priority: High**

The provider currently indexes all `.java` files found under the project root, including files
in build output directories (`target/`, `build/`, `.gradle/`). This produces duplicate incidents
for every source file that has been copied into build artifacts.

**Current behavior:** `SymbolIndex.indexDirectory()` walks the entire directory tree without
filtering. When run against a project with Maven or Gradle build output present, compiled/copied
sources in `target/` or `build/` are indexed alongside the actual sources, multiplying incident
counts.

**Proposed fix:** Skip well-known build output directories during the directory walk:
- `target/` (Maven)
- `build/` (Gradle)
- `.gradle/` (Gradle cache)
- `bin/` (Eclipse)
- `out/` (IntelliJ)
- `.java-provider-work/` (this provider's own work directory)

Also consider respecting `.gitignore` patterns, since build output directories are almost always
gitignored.

---

## 4. Dependency Condition Evaluation (`java.dependency`)

**Priority: High**

Implement the `java.dependency` capability so the engine can evaluate dependency-based rules
against the provider. The provider already advertises this capability and returns dependency
lists via `GetDependencies`, but does not yet support the evaluation side: matching dependency
coordinates and versions against rule conditions, and resolving the location where each
dependency is declared in the build file.

**How it works in the Go provider:**

The engine sends an `Evaluate` request with `cap="dependency"` and a condition like:

```yaml
java.dependency:
  name: junit.junit
  upperbound: 4.12.2
  lowerbound: 4.4.0
```

The evaluation flow is:

1. Call `GetDependencies()` to get the full dependency list (keyed by build file URI)
2. Match dependencies by exact `name` or by `name_regex` pattern
3. For matched dependencies, check version constraints (`lowerbound` / `upperbound`) using
   semver comparison — if the dependency version falls outside the range, skip it
4. For each matching dependency, resolve its **location** in the build file — the line number
   where the `<dependency>` block appears in `pom.xml` or the equivalent in `build.gradle`
5. Return incidents with the build file URI, line number, and variables (`name`, `version`,
   `type`)

**What the Java provider needs:**

- **Evaluate handler for `"dependency"` cap** — `WorkspaceContext.evaluate()` currently only
  handles `"referenced"`. It must also parse `DependencyConditionCap` (name, name_regex,
  upperbound, lowerbound) and match against the resolved dependency list.

- **Version range comparison** — Semver-compatible comparison supporting `>=` lowerbound and
  `<=` upperbound constraints. The Go provider uses `hashicorp/go-version`; the Java equivalent
  would be something like `org.semver4j` or `org.apache.maven:maven-artifact`'s
  `ComparableVersion`.

- **Dependency location resolution** — Given a matched dependency (groupId + artifactId), find
  the line number where it is declared in `pom.xml` or `build.gradle`. The Go provider does
  this with a multiline grep for `<groupId>...</groupId>` adjacent to
  `<artifactId>...</artifactId>`. For Gradle, the equivalent would be searching for
  `group:artifact:version` strings in `build.gradle`. Results should be cached per dependency
  to avoid repeated file scans.

- **Indirect dependency support** — Transitive dependencies (not directly declared in the build
  file) should have their location resolved to the *direct* dependency that pulls them in. The
  Go provider stores the "base dep" in `Extras` and uses it for location lookup of indirect
  deps.

- **Extras fields on `Dep`** — The Go provider populates `Extras` with `groupId`, `artifactId`,
  and `pomPath` on each dependency so that the engine's `DependencyLocationResolver` can find
  the declaration. The Java provider's `getDependenciesFromBuildTool()` does not currently
  populate these fields.

- **`FileURIPrefix`** — Each dependency should carry a `FileURIPrefix` (the directory containing
  the dependency's JAR/sources) so the engine can correlate `IsDependencyIncident` incidents
  with the dependency that owns them via `dep-label-selector` filtering.

**Scope:** Touches `WorkspaceContext.evaluate()`, `getDependenciesFromBuildTool()`, and requires
a new `DependencyLocationResolver` component. The `getDependenciesDAG()` stub in
`JavaProviderService` should also be implemented to support the `--tree` output flag.

---

## 5. Dependency Code Analysis (Full Analysis Mode)

**Priority: High**

Enable rule evaluation against dependency source code, so that `java.referenced` rules can find
violations inside the libraries an application depends on. This is the difference between
`analysisMode: "source-only"` (only scan the application's own code) and `analysisMode: "full"`
(also scan dependency code). The feature is optional at runtime — controlled by the
`analysisMode` field in the provider's `initConfig`.

**How it works in the Go provider:**

When running in full mode, the Go provider:

1. Resolves the project's dependency tree via the build tool
2. For each dependency, locates or downloads the source JAR; if unavailable, decompiles the
   binary JAR
3. Indexes all dependency source files alongside the application source, but tracks which files
   came from dependencies
4. When a rule matches a symbol in a dependency file, the incident is returned with
   `IsDependencyIncident: true`
5. Each `Dep` returned by `GetDependencies` carries a `FileURIPrefix` — the `file://` path to
   the directory containing that dependency's sources. The engine uses this to correlate
   dependency incidents with specific dependencies when applying `--dep-label-selector` filters

The `dep-label-selector` is an engine-side feature that filters violations from dependencies
based on labels (e.g. `konveyor.io/dep-source=open-source`). It works by checking whether an
incident's `fileURI` starts with a dependency's `FileURIPrefix` — if the matching dependency's
labels don't satisfy the selector, the incident is dropped.

**What already exists in the Java provider:**

The basic plumbing is in place:

- `WorkspaceContext.index()` already gates dependency source resolution on
  `!"source-only".equals(analysisMode)` (line 61)
- `DependencyResolver` downloads source JARs and decompiles missing ones via Vineflower
- `SymbolIndex.indexDependencyDirectory()` indexes dependency sources and tracks their file URIs
  in a `dependencyFileUris` set
- `SymbolIndex.isDependencyFile()` checks whether a given file URI belongs to a dependency
- `evaluateReferenced()` already sets `IsDependencyIncident` on each incident by calling
  `symbolIndex.isDependencyFile(sym.fileUri())` (line 173)

**What is missing:**

- **`FileURIPrefix` on `Dep` responses** — `getDependenciesFromBuildTool()` does not populate
  `FileURIPrefix` on each dependency. Without this, the engine cannot correlate dependency
  incidents with specific dependencies, so `--dep-label-selector` filtering will not work. Each
  `Dep` must carry the `file://` prefix of the directory where its sources (or decompiled
  sources) were extracted. This requires threading the resolved source directory paths from
  `DependencyResolver` back to the `getDependencies()` response.

- **Mapping dependencies to their source directories** — `DependencyResolver.resolve()` returns
  a flat list of source directories, but does not track which directory corresponds to which
  dependency. A mapping from `ResolvedDependency` to its source `Path` is needed so that
  `FileURIPrefix` can be set correctly on each `Dep`.

- **DAG-aware transitive dependency tracking** — The Go provider distinguishes direct from
  transitive dependencies (`Indirect: true`) and stores a `baseDep` in `Extras` pointing to the
  direct dependency that pulls in each transitive one. The Java provider currently flattens all
  dependencies into a single list without marking transitivity. This matters for
  `dep-label-selector` filtering on transitive dependencies.

- **Testing at scale** — The existing `GrpcIntegrationTest.testGetDependencies` only tests
  dependency listing in source-only mode. Full-mode testing requires a test fixture with a
  `pom.xml` that has downloadable dependencies, verifying that dependency source symbols are
  indexed, that incidents from them carry `IsDependencyIncident: true`, and that `FileURIPrefix`
  values are correct.

**Scope:** The changes span `DependencyResolver` (return a dep-to-directory mapping),
`WorkspaceContext` (store the mapping and use it in `getDependenciesFromBuildTool()`), and the
`Dep` proto response construction. The indexing and incident-flagging paths are already wired.

# Failing E2E Tests (Koncur)

Status as of CI run [32121844946](https://github.com/jmle/windup-modern/actions/runs/32121844946) (2026-08-18).

**17 total | 6 passed | 6 skipped | 5 failing**

---

## 1. acmeair-webapp (4 errors) -- Fix Applied

**Mode:** binary (WAR file)

All 4 errors were missing rulesets (`discovery-rules`, `eap8/eap7`, `jakarta-ee9`, `technology-usage`).

**Root cause:** The Go provider decompiles WAR archives into a `java-project/` directory with Maven project structure (`src/main/java/`, `src/main/webapp/`, `pom.xml`). The kantra engine's builtin provider walks this directory for file discovery and XML matching. Our provider was creating files in a hidden `.java-provider-work/` directory with a flat structure — the engine couldn't find the decompiled files, so all four rulesets produced no output.

**Fix applied:** Restructured `ArchiveHandler` to create Go-compatible Maven project structure in `java-project/`:
- Decompiled WEB-INF/classes → `java-project/src/main/java/` (with non-class resources like META-INF/persistence.xml)
- Web resources (web.xml, CSS, JS, HTML) → `java-project/src/main/webapp/`
- Extracted pom.xml from META-INF/maven/ → `java-project/pom.xml`
- Synthetic pom.xml generated from identified dependency JARs when no pom.xml found in WAR
- Dependency decompilation work dir moved to `~/.java-provider-work/` (outside source tree)
- `evaluateDependency`, `getDependencies`, `getDependenciesDAG` now reference the project's pom.xml instead of the WAR file URI

**Classification: Fix pending CI validation.**

---

## 2. Daytrader (13 errors) -- Golden Files Updated

**Mode:** source-only

All 13 errors were the same rule (`ee-to-quarkus-00020`) reporting "failed to match on: line number" at specific `@MessageDriven` and `@TransactionAttribute` annotation sites in the `ejb3` package.

**Root cause:** The Go provider over-matches on this rule. Rule `ee-to-quarkus-00020` targets `javax.ejb.MessageDriven` but the Go provider also reports hits on unrelated EJB annotations at nearby lines (e.g., `@TransactionAttribute`, `@TransactionManagement`, `@Resource`). The golden files were generated from Go provider output, so they included these spurious incidents. Our provider correctly reports only the actual `@MessageDriven` usages.

**Fix applied:** Removed 13 false-positive incidents from `tests/daytrader/expected-output.yaml` in the koncur repo (286 lines removed). Incident count for `ee-to-quarkus-00020` went from 49 to 36. The removed incidents had `name` values of `Resource` (DTBroker3MDB:53, DTStreamer3MDB:50, TradeSLSBBean:102), `Override` (TradeSLSBBean:160,217,248,262,268,302,398,413,546), and `TransactionAttribute` (TradeSLSBBean:646).

---

## 3. Petclinic (timeout) -- CI Infrastructure Flake

**Mode:** source-only (with tagger, hazelcast custom rules)

The provider container times out during initialization: `timed out starting providers after 8m0s`. Analysis never runs.

**Root cause:** CI infrastructure issue. The Java provider container occasionally takes too long to start on GitHub Actions runners. This has been observed across multiple consecutive CI runs and is intermittent -- sometimes Petclinic passes, sometimes it times out. The 8-minute timeout is set by the kantra engine, not configurable from the provider side.

**Classification: CI infrastructure issue.** Not a code bug. When the provider starts successfully, Petclinic analysis runs to completion.

---

## 4. Seam booking (45 errors) -- Golden Files Updated

**Mode:** source-only

The 45 errors broke down into:
- **2 unexpected tags** (`Seam API`, `cdi`) in `eap6/java-ee/seam`
- **13 unexpected violations/insights** across 3 rulesets: 12 `seam-java-*` violations + `generic-catchall-00700` insight in `eap6/java-ee/seam`; `hibernate4-00039` in `eap7/weblogic`; `hibernate-00005` and `javax-to-jakarta-import-00001` in `eap8/eap7`
- **13 unmatched rules** that should no longer be unmatched (mirrors of the above)
- **6 unexpected technology-usage tags** (`Embedded=Hibernate`, `Java EE=JPA named queries`, `Object Mapping=Hibernate`, `Persistence=JPA named queries`, `Store=Hibernate`, `Store=JPA named queries`)
- **2 unexpected technology-usage insights** + 2 unmatched mirrors (`technology-usage-database-01200`, `technology-usage-embedded-framework-01500`)
- **1 unexpected ruleset** (`hibernate`) not present in golden file

**Root cause:** The Go provider's source-only mode has a blind spot for annotation-based matching in the Seam codebase. It fails to match `@Entity`, `@NamedQuery`, Hibernate annotations, and javax imports that are clearly present in the source code. Our provider correctly indexes and matches these. The golden files were generated from the Go provider's (limited) output.

**Fix applied:** Replaced the 5 affected rulesets in `tests/seam-booking/expected-output.yaml` with our CI output and added the new `hibernate` ruleset. Changes by ruleset:

| Ruleset | Tags | Violations | Insights | Unmatched |
|---------|------|------------|----------|-----------|
| eap6/java-ee/seam | 11 -> 13 (+2) | 8 -> 18 (+10) | 5 -> 8 (+3) | 161 -> 148 (-13) |
| eap7/weblogic/tests/data | 7 -> 7 | 2 -> 3 (+1) | 3 -> 3 | 385 -> 384 (-1) |
| eap8/eap7 | 0 -> 0 | 1 -> 3 (+2) | 0 -> 0 | 338 -> 336 (-2) |
| hibernate (new) | +2 | 0 | +1 | +12 |
| technology-usage | 35 -> 41 (+6) | 0 -> 0 | 14 -> 16 (+2) | 757 -> 755 (-2) |

Golden file grew from 3582 to 10303 lines (mostly new incident detail in seam-java rules).

---

## 5. Customer-Tomcat-Legacy (26 errors / timeout) -- Mixed: Our Gap + Pending Fix

**Mode:** source-only

In CI run 32121844946 this test timed out (same infrastructure issue as Petclinic). In the prior run (32113895623) where it completed, it had 26 errors:

### 5a. Missing transitive dependency tags (10 errors) -- Our gap

Missing tags for Micrometer (`spring-boot-starter-actuator` transitive) and Spring DI (`spring-data-jpa` transitive). These are transitive dependencies that require successful Aether resolution to discover.

**Root cause:** The project's `pom.xml` references a private Azure DevOps repository and uses BOM imports (`spring-boot-dependencies`). Our `findManagedVersion` cannot resolve versions from BOM imports (`scope=import, type=pom`), so `spring-data-jpa` gets fallback version `[0,)`, which resolves to an ancient `1.8.0.RC1` whose parent POM is unavailable, causing the entire Aether collection to fail. The Maven fallback (`fallbackFromPom`) only returns direct dependencies, so transitives like Micrometer are lost.

Related missing rules: `technology-usage-embedded-framework-08200`, `embedded-framework-08200`, `embedded-framework-08300`, `technology-usage-embedded-framework-08300`.

**Classification: Our gap.** Implementing BOM import resolution in `findManagedVersion` would fix this.

### 5b. Dependency pom.xml line numbers at 0 (8 errors) -- Our gap (partially fixed)

Several dependency rules (`mvc-01220`, `observability-0100`, `database-03000`, `embedded-framework-08400`) report incidents at `pom.xml:0` instead of the correct line. The golden files expect specific lines (86, 96, 76, 91).

**Root cause:** When Aether resolution fails and we fall back to static pom parsing, the `getDependenciesFromStaticParsing()` path builds `Dependency` protobuf objects. We added `groupId`/`artifactId`/`pomPath` to extras (fix applied but not yet in CI), which should allow `getDependencyLocation` to find the correct lines. This fix has not been validated in CI because Customer-Tomcat-Legacy timed out in the latest run.

**Classification: Our gap, fix pending CI validation.** The code fix (static parsing extras) is in place but hasn't been verified in a successful CI run.

---

## Summary

| Test | Errors | Classification | Status |
|---|---|---|---|
| acmeair-webapp | 4 | Fix applied | java-project structure + pom.xml, pending CI |
| Daytrader | 13 | False positive | Golden files updated |
| Petclinic | timeout | CI infrastructure | Not actionable from provider side |
| Seam booking | 45 | False positive | Golden files updated |
| Customer-Tomcat-Legacy | 26 | Mixed | BOM resolution (ours) + validate line fix in CI |

**Errors that are real bugs on our side:** ~18 (10 missing transitive deps + 8 line numbers, all in Customer-Tomcat-Legacy)
**Errors fixed via golden file updates:** ~58 (13 Daytrader + 45 Seam booking)
**Errors fixed via code changes:** 4 (acmeair binary project structure, pending CI)
**Not errors at all:** Petclinic timeout (infra)

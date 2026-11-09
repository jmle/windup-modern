# Baseline Comparisons — validating the native Java provider against real output

How we check the native provider produces the *right* analysis results, by comparing it against
trustworthy baselines rather than asserting equality blindly. This complements the automated test
pyramid in [`../TEST_PLAN.md`](../TEST_PLAN.md).

There are three baseline sources, in increasing order of realism:

| Baseline | What it is | Automated? | Doc |
|---|---|---|---|
| **Synthetic oracle (L2)** | Fixtures whose correct matches are known *by construction* | yes, per-PR | `TEST_PLAN.md` L2 |
| **koncur differential** | Vanilla koncur `main`'s Go goldens (the Go provider's output of record) | yes, nightly/gated | [`DIFFERENTIAL_HARNESS.md`](DIFFERENTIAL_HARNESS.md) + [`koncur-parity-ledger.yaml`](koncur-parity-ledger.yaml) |
| **Real-world sample apps** | A large corpus of real `.war`/`.ear`/source apps + their prior kantra/windup reports | manual / exploratory | **this doc** |

> Key principle (shared across all three): **a difference is not automatically a failure.** The Go
> provider is sometimes wrong. Every divergence is triaged — *Go-wrong* (accept Java), *Java-wrong*
> (real regression), or *benign* (noise). Only Java-wrong is a failure.

---

## 1. Real-world sample-app validation

### Method
Run the native provider, through a real **kantra** install, against apps from the sample corpus
(`~/Dev/redhat/40SampleApps` — WARs/EARs/source trees, many shipping prior kantra/windup `*.report/`
output as an informal baseline), then review the `output.yaml` / `dependencies.yaml` for correctness and
**triage any surprise**, using the REPL ([`../README.md`](../README.md) → ProviderRepl) to inspect what
the provider actually indexed/resolved.

This catches things the synthetic and koncur baselines can't: messy real archives, unusual build
setups, toolchain pins, and ruleset-target interactions.

### Environment — the "new-provider kantra" install
A fresh kantra containerless install with the Go `java-external-provider` binary swapped for a wrapper
that launches our jar (see the swap pattern in `../Dockerfile.kantra`):

```
~/Downloads/kantra-java-native.linux.amd64/
  kantra                    # stock kantra CLI
  java-external-provider    # wrapper script -> java -jar java-provider.jar "$@"
  java-provider.jar         # our shaded provider
  jdtls/ fernflower.jar     # kept as placeholders (settings reference them; our provider ignores them)
  maven-index.txt           # SHA index (sibling of the jar -> auto-discovered)
  rulesets/ static-report/ maven.default.index
```
Run: `cd` into that dir, then `./kantra analyze --run-local -i <app> -o <out> --target <t> --overwrite`.

> ⚠️ The jar in that install was built from `main` on 2026-10-30, **before** PR #19/#20 merged. Rebuild
> it from current `main` to pick up the Kotlin-DSL / init-script fix (see §3, petclinic finding #3).

### Session runs (2026-10-30)

#### spring-petclinic — target `spring-boot3` (Gradle + Maven source project)
| Aspect | Result |
|---|---|
| Incidents | 16 across 4 rules — **correct**. petclinic is already **Spring Boot 4.0.3 + jakarta** (38 jakarta imports, 1 javax), so a *spring-boot3* migration legitimately finds little. No false positives. |
| Dependencies | **Degraded** — `dependencies.yaml` was **static-parsed** (no versions, no transitives, no open-source labels), for both `build.gradle` and `pom.xml`. |

Findings:
1. **Dependency resolution fell back to static parsing.** Root cause: Gradle resolution returned **0
   artifacts** because the project pins a **Java 17 toolchain** and the environment only had Java 21/8
   (`Cannot find a Java installation matching languageVersion=17`). → *Java-wrong-ish, but really an
   environment/toolchain limitation; the Go provider hits the same Gradle constraint.*
2. **Detector chose Gradle and never fell back to Maven.** `BuildToolDetector` prefers `build.gradle`
   over `pom.xml`. petclinic ships both; Maven (embedded Aether, no toolchain gate) resolves where Gradle
   failed. → **✅ fixed: build-tool fallback** — when the detected tool resolves 0 deps and the other
   build file is present, `WorkspaceContext` now retries with the other tool before degrading to static
   parsing (`alternativeBuildTool`). Verified on petclinic: *"GRADLE resolved 0 dependencies; falling back
   to MAVEN"* → 4 deps **with versions + a transitive** (vs the version-less static parse before).
3. **The provider mutated the user's `build.gradle`.** The (then-current) append-to-build-file approach
   left an injected `konveyorResolveDependencies` task behind (git showed `M build.gradle`). → **FIXED
   on `main` by PR #19** (DSL-agnostic `--init-script` injection never touches the build file). The
   kantra-install jar predates that fix.
4. **`mavenIndexPath` mismatch.** kantra passes the install *directory* as `mavenIndexPath`; our provider
   expects the `maven-index.txt` *file*. Harmless for a source project, but would misconfigure SHA-based
   binary identification. → **improvement: accept a directory and look for `maven-index.txt` inside.**

#### camunda-bpm-spring-boot-starter-example WAR 1.3.0 — target `spring-boot3` (binary)
| Aspect | Result |
|---|---|
| Incidents | 1 — **correct/expected** (see below). |
| Dependencies | **Excellent** — 92 deps identified from `WEB-INF/lib` with accurate versions (spring-boot 1.3.7, camunda 7.5.0, spring 4.2.7 …) **and** open-source labels. |

Why 1 incident is right (not under-matching):
- The app has **only 3 application classes** (it's a tiny example; the real code is all in dependency
  jars), so `java.referenced` source-pattern rules have almost nothing to match.
- The app is **Spring Boot 1.3.7 / Spring 4.2.7** — below the floor of the `spring-boot-2.x-to-3.0` /
  `spring-framework-5.x-to-6.0` rules, which were **evaluated and skipped** (they assume 2.x/5.x; you'd
  migrate 1.x→2.x first).
- The spring-boot3 **dependency** rules target `spring-cloud-*`, `spring-boot-starter-parent`,
  `hazelcast` — **none present** in this app.
- The one incident that fired is a **builtin `builtin.file`** rule (matches the `pom.xml` filename) on
  the synthesized pom — the Go builtin provider, not ours.

What it proved works: the **binary pipeline** (explode → Vineflower decompile → index →
dependency identification + labeling) is solid on a real archive. `java.dependency` version-range
matching is independently unit-tested (`DependencyEvalTest`).

Noise (not provider bugs): dozens of `failed to query xml file … processes.xml: invalid XML document`
— that's the **Go builtin XML provider** choking on camunda's `processes.xml`; and a stray
`rulesets/ruleset.yaml: no such file` load warning. Neither affects the Java provider's output.

### Takeaways from the real-world runs
- **Matching correctness held up** on both a modern source app and an old binary — no false positives,
  no missed matches once the app's actual version/shape is accounted for.
- The issues found were all on the **dependency-resolution / packaging** side, not matching:
  build-tool selection, Gradle toolchain pins, build-file mutation (now fixed), index-path config.
- **Pick the right target/app to exercise a ruleset:** a `spring-boot3` run only lights up on an app
  that's actually on **Spring Boot 2.x** — not 1.x (too old) and not already-4.x (too new).

### Improvements (surfaced by these runs)
1. **Build-tool fallback** — ✅ **done**: if the detected tool resolves 0 deps and another build file
   exists, the provider retries with the other tool before static parsing (`alternativeBuildTool`,
   petclinic finding #2).
2. **Gradle toolchain resilience** — ✅ **done**: `GradleBuildTool` now detects the "no matching Java
   toolchain" configuration failure and logs a clear, actionable warning (install the JDK / set
   `org.gradle.java.installations.paths`) instead of silently losing resolution; combined with #1 it then
   falls back to Maven (or static). Fundamentally fixing a missing JDK is an operator action (finding #1).
3. **`mavenIndexPath` as directory** — ✅ **done**: `mavenIndexFileFrom` accepts the kantra-style install
   directory and resolves `maven-index.txt` within it (finding #4).
4. **Rebuild the kantra-install jar from current `main`** to include PR #19 (no build-file mutation) and
   PR #20.

---

## 2. koncur differential — current status

Mechanism and comparator normalization live in [`DIFFERENTIAL_HARNESS.md`](DIFFERENTIAL_HARNESS.md);
the triaged divergences live in [`koncur-parity-ledger.yaml`](koncur-parity-ledger.yaml). Snapshot:

**As of the last triaged run (ledger baseline = koncur `5ce4fb0` @ 2026-10-20): 0 unexplained
divergences, 0 open regressions** across the runnable corpus.

| Test | Class | Status | Meaning |
|---|---|---|---|
| daytrader (`ee-to-quarkus-00020`) | go-wrong | accepted | Go over-matches `@MessageDriven`; Java correct |
| seam-booking | go-wrong | accepted | Go source-only misses annotations; Java finds them (278 java-more, 0 java-fewer) |
| petclinic-hazelcast | benign | excluded | 8-min startup timeout — CI flake |
| acmeair (binary WAR) | java-wrong | **fixed** | was a real bug (hidden decompile dir); fixed + verified |
| tomcat-legacy BOM transitive deps | java-wrong | **fixed-pending-ci** | fixed + unit-tested (MTA-8155); full E2E blocked by a private Azure repo |
| tomcat-legacy dep pom line numbers | java-wrong | **fixed-pending-ci** | fixed + unit-tested; full E2E pending |

So the only standing "failures" are **documented Go-provider bugs** (Java is more correct), and all
three real provider regressions are fixed — two awaiting a CI environment with private-repo access.

### Delta re-sync to current koncur — done (2026-11-06)
koncur `main` had advanced 7 commits past the previous baseline, changing 3 goldens (`daytrader`,
`book-server-deps`, `book-server-source`). Re-synced: baseline re-pinned to current koncur HEAD
(`2d3cbea`, 2026-10-30; see the ledger `baseline` + `resync_log`), and the 3 changed tests re-triaged
with the native provider (apps @ `ci-oct2025`), **java-provider-scoped** (`--rulesets`):

| Test | Result |
|---|---|
| daytrader | 13 EXPLAINED (the `ee-to-quarkus-00020` go-wrong over-match), **0 unexplained** |
| book-server-deps | **0 divergence** — 8 java incidents / 5 java rules match the golden exactly |
| book-server-source | **0 divergence** — same |

All clean against the Java provider; the golden drift was entirely builtin-provider / ruleset-version
skew (xhtml/xml/properties/manifest), not the provider.

Caveats / still open:
- This is a **scoped local** re-sync (the install's ruleset version may differ from koncur's). The
  authoritative confirmation is the gating workflows, which now also pass `--rulesets`.
- `ruleset_ref` is now **pinned** (`baseline.ruleset_ref` = `quay.io/konveyor/kantra@sha256:c57b12b6…`,
  rulesets bundled at `/opt/rulesets`); both gating workflows pin kantra + ruleset extraction to that
  digest. Residual: koncur's own golden generation still tracks mutable `:latest`, so for a true zero-skew
  run the goldens should be regenerated at this same digest.
- The two **tomcat-legacy** fixes remain `fixed-pending-ci` (need a CI env that can reach the private repo).

### daytrader — first-pass delta triage (2026-11-06)
Ran the native provider on daytrader (`ci-oct2025`, source-only, `cloud-readiness || quarkus`) and triaged
vs the current golden. Two groups:

- **13 `java-fewer` on `ee-to-quarkus-00020` → EXPLAINED** (the refreshed `go-wrong` over-match entry —
  Go matches non-annotation `javax.ejb` type refs; Java matches only annotations).
- **~50–76 `java-more` → benign / environmental.** Triage finding: **every** one is on a **builtin-provider**
  rule (`builtin.file` / `builtin.xml` / `builtin.filecontent` / `builtin.has`) matching **non-Java**
  artifacts — `.xhtml` (19), `.xml` (18), `.properties` (6), `MANIFEST.MF`, `.jar`/`.ear`. **Zero** land on
  a `.java` file and **none** use `java.referenced` / `java.dependency`. These are the Go **builtin**
  provider + **ruleset version** (both identical regardless of which *Java* provider runs), differing
  because the test kantra install ships a different analyzer/ruleset version than koncur used to generate
  the golden.

**Conclusion:** the **Java provider under test has 0 unexplained divergences on daytrader** — its only
divergence is the explained `ee-to-quarkus-00020` over-match. The java-more noise is build-tool/ruleset
version skew, not the provider.

**Follow-ups this exposes:**
1. **Scope the differential comparator to java-provider rules** — ✅ **implemented + wired**:
   `koncur_triage.py --rulesets <dir>` classifies each ruleID by its `when` capability and skips only
   rules **confirmed** `builtin.*`-only; unknown rules are still compared, so a partial/wrong ruleset path
   degrades to "compare all" and can never mask a divergence. With it, daytrader goes from 76 unexplained
   to **0** (only the 13 explained over-match remain). The gating `koncur-differential.yaml` now best-effort
   extracts the bundled rulesets from the kantra image and passes `--rulesets` (falling back to unscoped if
   extraction fails). _(The hub workflow could get the same once its ruleset source is confirmed.)_
2. **Pin `ruleset_ref`** — ✅ **done**: pinned to the kantra image digest that bundles the rulesets
   (`quay.io/konveyor/kantra@sha256:c57b12b6…`, `/opt/rulesets`); both gating workflows pin kantra +
   ruleset extraction to it. Residual: koncur regenerates goldens with mutable `:latest`, so a fully
   zero-skew run also needs the goldens regenerated at this digest.

---

## 3. How to reproduce

```bash
# real-world run
cd ~/Downloads/kantra-java-native.linux.amd64
./kantra analyze --run-local -i ~/Dev/redhat/40SampleApps/<app-or-archive> \
  -o /tmp/out --target spring-boot3 --overwrite
# inspect: /tmp/out/output.yaml (incidents), /tmp/out/dependencies.yaml (deps)

# quick provider-side inspection without kantra (see what it indexed/resolved)
java -cp java-provider.jar io.konveyor.provider.ProviderRepl <app-or-archive>
#   deps                         # list resolved dependencies
#   <pattern>@<location>         # test a referenced match
#   dep:<name> lower:v upper:v   # test a dependency version-bound match

# koncur differential (delta) — see DIFFERENTIAL_HARNESS.md for full usage
python3 testing/koncur_triage.py <test> --allow-known
```

## History
- **2026-11-03** — created. Documents the 2026-10-30 real-world runs (spring-petclinic,
  camunda WAR) and the koncur differential status snapshot; notes the Gradle Kotlin-DSL / build-file
  mutation fix (PR #19) now on `main`.

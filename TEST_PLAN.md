# Test Plan — Native Java Analyzer Provider

**Goal:** prove the native Java provider can safely substitute the Go + JDTLS provider in Konveyor — a
critical component — with demonstrable, auditable evidence rather than "it passed koncur."

**Tracking:** [MTA-8144](https://redhat.atlassian.net/browse/MTA-8144) (subtasks linked below).

---

## The core problem: the oracle

koncur does **exact-match** validation against golden `expected-output.yaml` files produced by
`koncur generate` — i.e. a snapshot of whatever provider ran, historically the **Go** provider.
That makes "match the old provider" the success criterion, which is wrong for a *replacement*:

1. **The baseline encodes Go's bugs.** `FAILING_TESTS.md` documents ~58 "failures" that were the Go
   provider being wrong (Daytrader over-matching, Seam source-only misses). Exact-match flags our
   *correct* output as a failure.
2. **Regenerating the golden from our provider is tautological.** Once a golden is captured from the
   Java provider, the test passes but proves nothing about correctness — only self-consistency.

Therefore koncur is **necessary but not sufficient**. This plan layers independent oracles on top.

> Note: some in-repo docs are partially stale — e.g. `LOCATION_DIFFERENCES.md` lists `ENUM` as
> unimplemented, but it is implemented and unit-tested (`SymbolIndexTest`). Treat the docs as leads to
> verify, not ground truth. Re-auditing them is part of this plan.

---

## Test layers (the pyramid)

### L1 — Unit / index (fast, deterministic, in-repo)
Already present: `SymbolIndexTest`, `SymbolCollectorTest`-style coverage, `MavenShaIndexTest`, etc.
Gaps to fill:
- gRPC contract tests for **every RPC incl. error paths**: unknown workspace id, malformed
  `conditionInfo`, empty results, Init failure, Stop cleanup.
- **Concurrency/thread-safety**: the engine's 10-worker pool fires concurrent `Evaluate` calls at one
  workspace (`ThreadLocal<Yaml>` + pattern cache). Load-test it.
- **Glob→regex** property/fuzz tests.

### L2 — Synthetic location/condition matrix (provider-independent oracle) ⭐
A self-contained fixture corpus where the correct matches are known **by construction**, driven through
the real `referenced` **evaluate** path (YAML condition → incidents), not compared to Go.
- Scaffolded: `src/test/java/io/konveyor/provider/index/LocationCoverageTest.java`.
- Covers every `location`: TYPE, IMPORT, ANNOTATION, INHERITANCE, IMPLEMENTS_TYPE, METHOD_CALL,
  CONSTRUCTOR_CALL, RETURN_TYPE, FIELD, METHOD, VARIABLE_DECLARATION, ENUM, PACKAGE, and default.
- Extend with: `annotated` elements, `filepaths` filtering, dependency-vs-application incident flag,
  glob variants. Turn each row of `LOCATION_DIFFERENCES.md` into a case.
- This is the highest-value oracle: deterministic, independent of both providers, catches gaps
  (default-location breadth, etc.) without kantra.

### L3 — Dependency resolution vs trustworthy external oracles
Don't compare deps to Go. Compare `GetDependencies` / `GetDependenciesDAG` against:
- **`mvn dependency:tree`** (authoritative for Maven) — incl. multi-module, **BOM imports**
  (`FAILING_TESTS.md` §5a gap), transitives, classifiers, scopes.
- **Gradle `dependencies` task** (Groovy + Kotlin DSL).
Also test: source-JAR download + decompilation fallback, open-source labeler, version bounds,
offline/proxy/private-repo.

### L4 — Differential E2E (koncur, both providers)
Run **both** providers over the same corpus, same engine, **pinned** rules/versions, and **diff**.
Triage every diff into:
- **(a) Java correct, Go wrong** → update baseline + record rationale
- **(b) Go correct, Java wrong** → real regression (the only true failures)
- **(c) benign** (ordering, dedup, line±1) → normalize the comparator
Output: a tracked **parity ledger** (formalize `MISSING_FEATURES.md` / `LOCATION_DIFFERENCES.md` /
`FAILING_TESTS.md`). koncur supports image overrides (`javaProvider:` / `JAVA_PROVIDER_IMG`) for this.

### L5 — Non-functional / production-readiness
- **Resource lifecycle & leaks:** `Stop` must remove the workspace *and* clean temp dirs
  (`java-provider-mvn-*`, `~/.java-provider-work/`, `java-project/`). Run many Init/Stop cycles; watch
  RSS + disk.
- **Performance regression gates:** track wall-clock, CPU, peak RSS, threads vs `RESOURCE_COMPARISON.md`
  baselines; fail CI on regression.
- **Soak/stress:** long-lived server, huge monorepos, deep archives.
- **Resilience:** malformed Java, truncated/corrupt archives, missing deps, network failure mid-resolution,
  air-gapped, private repo + proxy, the 8-min kantra startup timeout (flaked in CI).
- **Security:** zip-slip / path traversal on extraction, zip-bomb / resource exhaustion, untrusted
  bytecode during decompile.
- **Determinism:** same input → same incident *set* across runs (compare order-insensitive).

### L6 — Integration surfaces (as Konveyor runs it)
- **kantra** containerless (`--run-local`) and container mode.
- **Tackle Hub in-cluster** (koncur patches the Tackle CR image) — the production path.
- **Compatibility matrix:** kantra/analyzer versions × Java 8/17/21 bytecode × Maven (multi-module + BOM)
  / Gradle (Groovy + KTS) × source-only / full / binary (WAR/EAR/JAR) / `mvn://`.

---

## koncur review checklist
- [ ] Audit which goldens were regenerated from **Java** vs still **Go** (Java ones are self-confirming →
      need L2/L4 to actually prove correctness).
- [ ] **Pin** kantra, ruleset/analyzer-lsp, and both provider image tags; record them per run.
- [ ] Regenerate Go baselines at **current** rules; re-run and re-triage `FAILING_TESTS.md`.
- [ ] Expand beyond the ~17 apps; add missing **Gradle**, **binary/`mvn://`**, and **full-mode** coverage.

---

## Process
- **CI per PR:** L1 + L2 + fast koncur subset.
- **Nightly:** full koncur corpus (differential) + L3 + L5 perf/soak.
- **Shadow/canary:** run Java provider alongside Go on real analyses and diff *before* flipping default.

## Exit criteria for the switch
- [ ] Zero **bucket-(b)** correctness regressions across the corpus.
- [ ] Dependency parity vs Maven/Gradle within agreed tolerance (BOM imports resolved).
- [ ] All `location` types + `referenced`/`dependency` features covered by the L2 synthetic oracle.
- [ ] Perf within bounds of `RESOURCE_COMPARISON.md`; no CI perf regression.
- [ ] Soak/leak test clean (RSS + disk stable over N Init/Stop cycles).
- [ ] Parity ledger reviewed and signed off.

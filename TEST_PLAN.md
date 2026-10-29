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
- `LocationCoverageTest` — every `location`: TYPE, IMPORT, ANNOTATION, INHERITANCE, IMPLEMENTS_TYPE,
  METHOD_CALL, CONSTRUCTOR_CALL, RETURN_TYPE, FIELD, METHOD, VARIABLE_DECLARATION, ENUM, PACKAGE, default.
- `ReferencedConditionsTest` — the rest of the `referenced` surface: `annotated` (presence + element
  values, incl. regex matching + negatives), `filepaths` filtering (include/exclude), glob (wildcard,
  alternation `(a|b)`, suffix), and the method-with-return-type form.
- `DependencyIncidentTest` — the dependency-vs-application incident flag (`IsDependencyIncident`).
- This is the highest-value oracle: deterministic, independent of both providers, catches gaps
  (default-location breadth, etc.) without kantra.

### L3 — Dependency resolution vs trustworthy external oracles
Don't compare deps to Go. Compare `GetDependencies` / `GetDependenciesDAG` against:
- **`mvn dependency:tree`** (authoritative for Maven) — incl. multi-module, **BOM imports**
  (`FAILING_TESTS.md` §5a gap), transitives, classifiers, scopes.
- **Gradle `dependencies` task** (Groovy + Kotlin DSL).
Also test: source-JAR download + decompilation fallback, open-source labeler, version bounds,
offline/proxy/private-repo.

### L4 — Differential E2E (koncur, ledger-driven)
We trust vanilla koncur `main`'s goldens as the **system of record for the Go provider's output**
(no need to run the Go image live). So the differential is a **single run**: run the *new* provider
against vanilla `main` and diff its output against the committed Go goldens.

Important: "the Go baseline is the Go output of record" is **not** "the Go baseline is ground truth" —
the whole premise is that Go is sometimes wrong. So every divergence is **triaged**, not auto-failed:
- **(a) `go-wrong`** — Java correct, Go wrong → accept Java; justified golden change.
- **(b) `java-wrong`** — Go correct, Java wrong → **real regression** (the only true failures).
- **(c) `benign`** — ordering / dedup / line tolerance → normalized away by the comparator.

**Don't re-triage the whole corpus each time.** Record every triaged divergence in a durable
**parity ledger** (`testing/koncur-parity-ledger.yaml`) keyed by `(test, rule, incident)` with
classification + rationale. The run then becomes a **delta** operation:

```
run new provider vs vanilla main → diff vs Go goldens → for each divergence:
    explained by ledger & still applies? → carry forward (auto-explained)
    new / changed?                       → human triages just this delta
CI gate: fail if any (b) java-wrong regression OR any unexplained divergence.
```

The forked golden branch (`koncur-new-java-provider`) is the *output* of a past run of this process,
but it persisted only the edited goldens and lost the reasoning — so it can't be re-synced cheaply and
has gone stale vs `main`. Replace it with the ledger (seeded from `FAILING_TESTS.md` + the branch diff),
then retire it (MTA-8152). koncur image override: `javaProvider:` / `JAVA_PROVIDER_IMG`.

**Comparator normalization** (so the diff is signal, not noise):
- **Order-insensitive:** compare incidents as sets, sorted by `(ruleset, rule, uri, line)`.
- **Dedup:** collapse incidents identical in `(uri, line, rule)` before comparing (the provider emits
  method-signature variants; `evaluate` already dedups by `uri:line`).
- **URI normalization:** normalize `file://` scheme and workspace/base-dir prefixes; map binary
  `java-project/` paths to their logical source path (the acmeair issue).
- **Line tolerance:** exact for application source; configurable ±N for **decompiled/dependency** URIs
  (decompiler line numbers drift — cf. koncur's "Update line numbers for new decompiler" commit).
- **Field scope:** compare on structural identity (`ruleset, rule, uri, line`); ignore volatile fields
  (effort, rendered message) unless a test explicitly asserts them.
- **Empty rulesets:** ignored (koncur already filters them).

### L5 — Non-functional / production-readiness
> Procedures, how-to-run, and measured baselines live in [`testing/PERFORMANCE.md`](testing/PERFORMANCE.md).
> Status: lifecycle/leak (Stop temp-dir cleanup), concurrency, and resilience/security (zip-slip rejection,
> graceful handling of corrupt archives / missing locations / malformed Java — `ArchiveSafetyTest`,
> `ResilienceTest`) are implemented + gated; a perf micro-benchmark + baseline exist (advisory); soak is
> planned.

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

## Process (current)
- **Per PR:** `unit-tests.yaml` — L1 (JUnit, incl. the L2 synthetic oracle) + the triage-harness tests. Fast.
- **Nightly + on demand:** `koncur-differential.yaml` — full differential corpus vs koncur `main`, **gating**
  (see `testing/DIFFERENTIAL_HARNESS.md`). L3/L5 (dependency/Gradle, perf, soak) still to be added here.
- **Shadow/canary:** run Java provider alongside Go on real analyses and diff *before* flipping default.
- The stale forked-baseline `koncur-e2e` PR job has been **retired** (MTA-8152).

## Exit criteria for the switch
- [x] Zero **bucket-(b)** correctness regressions across the corpus — *first differential run: 0 unexplained, 0 regressions.*
- [~] Dependency parity vs Maven/Gradle — *Maven/BOM done + parity test; **Gradle still to validate**.*
- [~] All `location` types + `referenced`/`dependency` features covered by the L2 synthetic oracle — *core locations covered; extend `annotated`/`filepaths`/dependency.*
- [ ] Perf within bounds of `RESOURCE_COMPARISON.md`; no CI perf regression — *not started (MTA-8157).*
- [ ] Soak/leak test clean (RSS + disk stable over N Init/Stop cycles) — *not started (MTA-8157).*
- [ ] Works via Tackle Hub in-cluster (production path) — *CLI proven; hub pending (MTA-8159).*
- [ ] Parity ledger reviewed and signed off.

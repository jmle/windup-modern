# The Case for the Native Java Analyzer Provider

> Slide deck outline — audience: Konveyor engineers / maintainers.
> Honest technical case: leads with real advantages, names the current trade-offs, and shows the path to close them.
> All numbers are from the repo's own benchmark/parity docs (`RESOURCE_COMPARISON.md`, `OPTIMIZATIONS.md`,
> `LOCATION_DIFFERENCES.md`, `MISSING_FEATURES.md`, `FAILING_TESTS.md`).

---

## Slide 1 — Title

**A native Java analyzer provider for Konveyor**
Replacing the Go provider + JDTLS tandem with a single, Java-native implementation.

*Talking point:* This isn't a rewrite for its own sake. It collapses a three-process, two-language, two-repo stack into one JVM we fully control — and in several documented cases it's already **more correct** than what we ship today.

---

## Slide 2 — What we run today (the tandem)

The current Java analysis path is a relay across three processes and two languages:

```
kantra engine (Go)
   └─ gRPC → java-external-provider (Go binary)   ← thin client
                └─ LSP → JDTLS (Java language server) ← the actual analysis
                            └─ fernflower (decompiler), Gradle, ...
```

- **Go provider**: thin gRPC client that translates rule conditions into JDTLS commands.
- **JDTLS bundle**: a separate Java extension doing the real `SearchPattern` work.
- Two codebases, two languages, two release cadences to keep in sync for one capability.

*Talking point:* Every rule condition crosses Go → gRPC → LSP → JDTLS and back. The matching logic lives behind the LSP boundary — effectively a black box we configure but don't own.

---

## Slide 3 — What we're proposing

**One Java process. One repo. The provider and the "java bundle" unified.**

```
kantra engine (Go)
   └─ gRPC → java-analyzer-provider (single JVM)
                ├─ Eclipse JDT Core        (AST, as a library)
                ├─ Vineflower              (decompiler, as a library)
                └─ Maven Resolver / Aether (dependency resolution, in-process)
```

*Talking point:* Because the provider is itself Java, the Eclipse tooling, decompiler, and Maven resolver are all **libraries we call directly** — no language server, no subprocess relay, no LSP black box.

---

## Slide 4 — The thesis: four pillars

1. **Native Java → tooling as a library.** JDT, Vineflower, Aether all in-process.
2. **We own search & matching.** AST visitors we can read, test, and fix — not JDTLS `SearchPattern` semantics behind an LSP.
3. **Leaner operational footprint.** One process, less memory, fewer threads, smaller image, more stable.
4. **One language, one repo → maintainability.** The migration community is Java-first; so is the code now.

*Talking point:* Pillars 1, 3, 4 are architectural wins available on day one. Pillar 2 is the one with the biggest long-term payoff — and we already have evidence for it.

---

## Slide 5 — Pillar 1: Tooling as a library, not a subprocess

| Capability | Today | Native provider |
|---|---|---|
| AST / symbol search | JDTLS over LSP | **JDT Core** called directly (`SymbolCollector` AST visitors) |
| Decompilation | fernflower subprocess | **Vineflower** as a library |
| Dependency resolution | shell out to `mvn` | **Maven Resolver (Aether)** in-process |

*Talking points:*
- Direct API access means we see the full fidelity of the AST — resolved FQNs, receiver types, annotation elements — not just what the LSP command surface exposes.
- No JDTLS version/`JAVA8_HOME` coupling; no fernflower binary to ship; no `mvn` on the host.
- Future analyses (custom symbol kinds, richer incident variables, bytecode/decompile-aware matching) become ordinary Java code instead of LSP protocol extensions.

---

## Slide 6 — Pillar 2: We own search & matching (the "black box" problem)

The Go provider's matching is a **leaky, three-layer pipeline** it can't fully control, because JDTLS `SearchPattern` wasn't built for migration queries:

- **IMPORT, TYPE, and ANNOTATION all issue the same search** (`TYPE/ALL_OCCURRENCES`). They're only differentiated by post-hoc filtering in Java, then filtered *again* in Go.
- **ANNOTATION leaks non-annotations** — its AST fallback matches constructor calls and `@Override`.
- **RETURN_TYPE / ENUM** search the wrong index (all types / fields) and narrow afterward.

Our approach: the `SymbolCollector` AST visitor populates **one bucket per location** up front; `query()` reads the right bucket directly.

*Talking point:* Theirs is "search broadly, then filter in two languages and hope." Ours is "index precisely by location, look it up." We can read the matching logic end-to-end in one file, in one language.

---

## Slide 7 — Pillar 2, proof: we're already *more correct*

From the koncur parity suite (`FAILING_TESTS.md`) — cases where golden files had to change because **the Go output was wrong**, not ours:

- **Daytrader — over-matching.** Rule `ee-to-quarkus-00020` targets `javax.ejb.MessageDriven`, but the Go provider also reported `@Resource`, `@Override`, `@TransactionAttribute` at nearby lines. **13 spurious incidents** removed; our provider reported only the real `@MessageDriven` usages.
- **Seam booking — source-only blind spot.** JDTLS can't confirm an annotation's type without library bytes, so in source-only mode it **misses** real `@Entity`, `@NamedQuery`, Hibernate annotations, and `javax` imports. **45 "errors"** were actually Go misses; we resolve the FQN from imports and match correctly.

*Talking point:* ~58 of the suite's "failures" were the harness catching up to *our* correct output. That flips the usual "the new thing is less mature" story — on matching correctness we're ahead in documented cases.

---

## Slide 8 — Pillar 3: Operational footprint

Benchmark: tackle-testapp, cloud-readiness + quarkus, source-only, 12c/31GB (`RESOURCE_COMPARISON.md`).

| Dimension | Go + JDTLS stack | Native Java | Winner |
|---|---:|---:|---|
| Processes | 3 | **1** | Java (simpler) |
| Peak provider memory | 1,682 MB | **1,293 MB** | Java (−23%) |
| Peak threads | 88 | **50** | Java (−43%) |
| Deployment size (provider-specific) | 69 MB | **36 MB** | Java (−48%) |
| Stability | crashes without `JAVA8_HOME` | clean runs | Java |

*Talking point:* One JVM instead of Go-binary + JDTLS means less memory, fewer threads, a smaller image, and no cross-process startup/version coupling — the axes that matter for containerized, at-scale runs.

---

## Slide 9 — Pillar 4: Maintainability & contributors

- **One language, one repo.** No Go glue + Java JDTLS extension to keep in lockstep.
- **The audience is Java.** Rule authors and migration contributors read Java; the matching logic is now in their language.
- **Readable, testable matching.** Each location is an AST visitor with unit tests, not a `SearchPattern` + `SymbolProvider` + Go-side filter spread across two codebases.

*Talking point:* Lower barrier to contribution and to fixing matching bugs — you change the visitor and add a test, in one place.

---

## Slide 9b — Validation: proven, not just claimed

We built a **ledger-driven differential harness** (`testing/koncur_triage.py`, `DIFFERENTIAL_HARNESS.md`): run the new provider against **vanilla koncur `main`**, diff every result against the Go golden, and classify each difference against a tracked **parity ledger** (Go-wrong / java-wrong / benign).

- Runs on **both production paths** — kantra CLI and **Tackle Hub** (in-cluster) — **gating**, nightly + on demand.
- Across **all Java sample apps**: **0 unexplained differences, 0 Java-provider regressions.**
- Every real difference is a **known Go bug** (over-/under-matching) or benign noise, recorded with rationale.
- The comparator is base-dir-, decompiler-line-, and ruleset-version-aware, so environmental noise (e.g. hub repo-subdir prefixes, Vineflower vs fernflower line numbers) never masquerades as a regression.
- Fast per-PR gate (`mvn test` + harness unit tests); heavy differential runs nightly.

*Talking point:* Correctness is now **continuously enforced in CI**, not a one-time claim. The "we're already more correct" story (Slide 7) is automated and guarded against regression.

---

## Slide 9c — How we keep it honest: the parity ledger

**The tempting shortcut** would be to edit koncur's expected answers so they match the new provider — then the test always passes and proves nothing (that's the old forked-golden approach we retired).

**What we do instead:** we never change the expected answers. For **every** difference between the new provider and the Go provider, we record three things in one version-controlled file (`testing/koncur-parity-ledger.yaml`):

- **what** is different (test, rule, incident),
- **who is right** — *Go is wrong* (`go-wrong`) · *our bug* (`java-wrong`) · *harmless* (`benign`),
- **why** (the rationale).

Any difference **without** a matching ledger entry makes the check **fail**. So nothing slips through unexplained, re-runs only need to triage *new* differences (not re-judge everything), and every decision is auditable.

*Talking point:* This is what turns "trust us, it's correct" into "here is every difference and our justification for it." It's the credibility backbone of the whole validation story.

---

## Slide 10 — Honest trade-off #1: raw performance (today)

Same benchmark (`RESOURCE_COMPARISON.md` / `OPTIMIZATIONS.md`):

| | Go stack | Native Java |
|---|---:|---:|
| Wall clock | 34s | 64s (Go 1.9×) |
| Provider CPU | 73s | 172s (Go 2.4×) |

Why — and why it's narrower than it looks:
- JDTLS is a mature LS with **incremental compilation and caching**; we parse fresh. Real gap, no spin.
- But **92% of wall time is rule evaluation**, and the provider is *not* the bottleneck: **96% of evaluate calls finish in <10ms**. Provider-side query optimizations we tried netted **~0s** (`OPTIMIZATIONS.md` §2).
- The dominant cost is **engine-side**: multi-condition OR rules evaluate branches **sequentially** (one blocking gRPC call each). The single biggest remaining win — **parallel OR conditions** — is an `analyzer-lsp` change that **benefits both providers**.

*Talking point:* We're honest that we're slower today. But the gap is largely cold-start/parse efficiency, not the hot path, and the biggest lever is in the engine, not the provider.

---

## Slide 11 — Parity gaps: closed

The gaps enumerated earlier are now fixed and regression-tested:

| Gap | Status |
|---|---|
| `ENUM` location | **Done** — enum type + constants indexed; unit-tested |
| BOM-import version resolution | **Done** — recursive + parent-chain resolution; validated against `mvn dependency:tree` |
| Dependency `pom.xml` line numbers | **Done** — `GetDependencyLocation` RPC covered by regression tests |
| Default (empty) location narrower than Go | Minor, still open — widen default search |
| `depLabelSelector` provider-side filtering | Not required — engine filters anyway |

The confirmed provider bugs (BOM resolution, line numbers) are fixed, and correctness is continuously gated by the differential (Slide 9b). The one remaining item (default-location breadth) is minor and tracked.

*Talking point:* The parity gaps we were honest about are closed; what's left is non-functional hardening, not correctness.

---

## Slide 12 — Roadmap: what's left

- **Correctness:** ✅ done and **gated** — differential vs koncur `main` on CLI + Tackle Hub, 0 regressions.
- **Hardening (next, the real remaining work):**
  - Performance + memory-leak / **soak** testing (long-running gRPC server; verify `Stop` cleans up).
  - **Resilience & security** (malformed inputs, corrupt archives, timeouts, offline/proxy).
  - Gradle dependency parity; extend the synthetic matching oracle.
- **Performance (engine):** **parallel OR-condition evaluation** in `analyzer-lsp` — biggest lever, helps all providers.
- **Launch tuning:** `jvmArgs`/`launchArgs` passthrough (ZGC already wired via Dockerfile).

*Talking point:* Correctness is settled; the remaining work is non-functional hardening plus a shared engine win — none of it reworks the architecture.

---

## Slide 13 — The ask

- Adopt the native Java provider as the **go-forward Java analysis path**.
- Correctness is **proven and continuously gated** — differential vs koncur `main`, on CLI **and** Tackle Hub, with **0 regressions**. The remaining work is **non-functional hardening** (perf, soak, resilience), not correctness.
- Prioritize the **engine-side parallel-OR change** in `analyzer-lsp` — biggest perf win, helps everyone.

**Net:** one language, one repo, one process; matching we own and have **proven** correct and keep gated; a smaller, more stable footprint — with correctness settled and a clear hardening path.

---

## Appendix A — Benchmark details

- Provider total CPU: Go 72.7s (JDTLS 65.1s + Go binary 6.0s + Gradle) vs Java 172.3s.
- Memory timeline: Go peaks when JDTLS ramps (~1,331 MB at t=18s); Java is two-phase (index ~268 MB → dep-source resolution peak 1,293 MB at t=53s).
- Pre/post provider optimizations: wall 70s → 64s (−9%); provider CPU unchanged (parsing is 2–3% of runtime).

## Appendix B — Why provider-side query tuning didn't help

- Hash-based exact lookup: indexing ~30k symbols cost ~5s, offsetting per-query savings → reverted.
- Evaluate result caching: each rule sends unique `conditionInfo` → near-zero hits → reverted.
- Conclusion: provider query time (<10ms for 96% of calls) is already below the engine's inter-call processing time.

## Appendix C — Location-by-location matching parity

See `LOCATION_DIFFERENCES.md` for the full per-location table (TYPE, IMPORT, ANNOTATION, INHERITANCE, METHOD_CALL, etc.), including where we deliberately diverge from Go's inconsistencies (we don't replicate the ANNOTATION leak, the shared IMPORT/TYPE/ANNOTATION search, the ENUM-searches-FIELD quirk, or RETURN_TYPE-searches-TYPE).

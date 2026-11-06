---
marp: true
theme: default
paginate: true
size: 16:9
style: |
  /* ---- Konveyor brand ---- */
  :root {
    --navy: #333481;
    --gold: #B09060;
    --red:  #E02030;
    --ink:  #3f3f46;
  }
  section {
    font-family: Arial, Helvetica, sans-serif;
    font-size: 24px;
    color: var(--ink);
    padding: 44px 60px 96px 60px;
    background-color: #fff;
    /* navy logo + navy stripe band on every content slide */
    background-image: url('assets/konveyor-navy.png'), url('assets/stripe-navy.png');
    background-repeat: no-repeat, no-repeat;
    background-position: right 50px bottom 40px, bottom center;
    background-size: 150px auto, 100% 22px;
  }
  h1 { color: var(--navy); font-size: 42px; }
  h2 { color: var(--navy); font-size: 33px; }
  h3 { color: var(--navy); font-size: 27px; }
  strong { color: var(--navy); }
  a { color: var(--navy); }
  table { font-size: 21px; border-collapse: collapse; }
  th { background: var(--navy); color: #fff; }
  tr:nth-child(even) { background: #f4f4fa; }
  td, th { padding: 5px 12px; }
  code { background: #ececf6; color: var(--navy); }
  pre { font-size: 18px; }
  blockquote {
    border-left: 6px solid var(--gold);
    color: #2b2b35;
    background: #faf7f1;
    padding: 8px 20px;
  }
  /* page number bottom-left, navy (logo occupies bottom-right) */
  section::after {
    left: 40px; right: auto; bottom: 20px;
    color: var(--navy); font-weight: bold;
  }
  /* ---- Title / section (lead) slides: full navy ---- */
  section.lead {
    justify-content: center;
    text-align: center;
    color: #fff;
    background-color: var(--navy);
    background-image: url('assets/konveyor-white.png'), url('assets/stripe-white.png');
    background-position: right 50px bottom 36px, bottom center;
    background-size: 210px auto, 100% 26px;
  }
  section.lead h1, section.lead h2, section.lead h3, section.lead strong { color: #fff; }
  section.lead h1 { font-size: 52px; }
  section.lead a { color: #cfd0ef; }
  section.lead blockquote { color: #fff; background: rgba(255,255,255,0.08); border-left-color: var(--gold); }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# A native Java analyzer provider for Konveyor

### Replacing the Go provider + JDTLS tandem with a single, Java-native implementation

<br>

*Honest technical case — advantages, trade-offs, and the path to close the gap*

<!--
Speaker notes: This isn't a rewrite for its own sake. It collapses a three-process,
two-language, two-repo stack into one JVM we fully control — and in several documented
cases it is already MORE correct than what we ship today.
-->

---

## What we run today — the tandem

A relay across **three processes** and **two languages**:

```
kantra engine (Go)
  └─ gRPC → java-external-provider (Go binary)      ← thin client
             └─ LSP → JDTLS (Java language server)  ← the real analysis
                        └─ fernflower, Gradle, ...
```

- **Go provider**: thin gRPC client; translates rule conditions into JDTLS commands
- **JDTLS bundle**: separate Java extension doing the actual `SearchPattern` work
- Two codebases, two languages, two release cadences — for one capability

<!--
Every rule condition crosses Go → gRPC → LSP → JDTLS and back. The matching logic lives
behind the LSP boundary — a black box we configure but don't own.
-->

---

## What we're proposing

**One Java process. One repo. Provider + "java bundle" unified.**

```
kantra engine (Go)
  └─ gRPC → java-analyzer-provider (single JVM)
             ├─ Eclipse JDT Core        (AST, as a library)
             ├─ Vineflower              (decompiler, as a library)
             └─ Maven Resolver / Aether (dependency resolution, in-process)
```

Because the provider is **itself Java**, the Eclipse tooling, decompiler, and Maven
resolver are **libraries we call directly** — no language server, no subprocess relay,
no LSP black box.

---

## The thesis: four pillars

1. **Native Java → tooling as a library** — JDT, Vineflower, Aether all in-process
2. **We own search & matching** — AST visitors we can read, test, and fix
3. **Leaner operational footprint** — one process, less memory, smaller image, more stable
4. **One language, one repo → maintainability** — the migration community is Java-first

<br>

> Pillars 1, 3, 4 land on day one. **Pillar 2 has the biggest long-term payoff — and we already have evidence.**

---

## Pillar 1 — Tooling as a library, not a subprocess

| Capability | Today | Native provider |
|---|---|---|
| AST / symbol search | JDTLS over LSP | **JDT Core** called directly |
| Decompilation | fernflower subprocess | **Vineflower** as a library |
| Dependency resolution | shell out to `mvn` | **Maven Resolver (Aether)** in-process |

- Full AST fidelity — resolved FQNs, receiver types, annotation elements
- No JDTLS version / `JAVA8_HOME` coupling; no fernflower binary; no `mvn` on host
- Future analyses become **ordinary Java code**, not LSP protocol extensions

---

## Pillar 2 — We own search & matching

The Go provider's matching is a **leaky three-layer pipeline** on top of JDTLS
`SearchPattern`, which wasn't built for migration queries:

- **IMPORT, TYPE, ANNOTATION all fire the same search** (`TYPE/ALL_OCCURRENCES`) —
  differentiated only by post-hoc filtering, in two languages
- **ANNOTATION leaks non-annotations** — its AST fallback matches constructor calls, `@Override`
- **RETURN_TYPE / ENUM** search the wrong index, then narrow afterward

**Ours:** the `SymbolCollector` AST visitor fills **one bucket per location** up front;
`query()` reads the right bucket directly.

<!--
Theirs: "search broadly, then filter in two languages and hope."
Ours: "index precisely by location, look it up." Readable end-to-end, one file, one language.
-->

---

## Pillar 2 — Proof: we're already *more correct*

From the koncur parity suite — golden files changed because **Go was wrong, not us**:

- **Daytrader — over-matching.** `ee-to-quarkus-00020` targets `javax.ejb.MessageDriven`,
  but Go also reported `@Resource`, `@Override`, `@TransactionAttribute`.
  **13 spurious incidents removed.**
- **Seam booking — source-only blind spot.** JDTLS can't confirm an annotation's type
  without library bytes, so it **misses** real `@Entity`, `@NamedQuery`, Hibernate
  annotations, `javax` imports. **45 "errors" were actually Go misses** — we resolve the
  FQN from imports and match.

> ~58 suite "failures" were the harness catching up to *our* correct output.

---

## Pillar 3 — Operational footprint

*tackle-testapp · cloud-readiness + quarkus · source-only · 12c / 31 GB*

| Dimension | Go + JDTLS | Native Java | Winner |
|---|---:|---:|---|
| Processes | 3 | **1** | Java |
| Peak provider memory | 1,682 MB | **1,293 MB** | Java (−23%) |
| Peak threads | 88 | **50** | Java (−43%) |
| Deployment size | 69 MB | **36 MB** | Java (−48%) |
| Stability | needs `JAVA8_HOME` | clean runs | Java |

One JVM instead of Go-binary + JDTLS — the axes that matter for containerized, at-scale runs.

---

## Pillar 4 — Maintainability & contributors

- **One language, one repo** — no Go glue + Java JDTLS extension to keep in lockstep
- **The audience is Java** — rule authors read Java; matching is now in their language
- **Readable, testable matching** — each location is an AST visitor with unit tests,
  not a `SearchPattern` + `SymbolProvider` + Go-side filter across two codebases

> Lower barrier to contribution and to fixing matching bugs: change the visitor, add a test — in one place.

---

## Validation — proven, not just claimed

A **ledger-driven differential**: run the new provider against vanilla koncur `main`,
diff every result vs the Go golden, classify each difference.

- Runs on **both production paths** — kantra CLI and **Tackle Hub** (in-cluster) — **gating**, nightly
- Across **all Java sample apps**: **0 unexplained differences, 0 regressions**
- Every difference is a **known Go bug** (over/under-matching) or benign — recorded in a parity ledger
- Comparator is base-dir / decompiler-line / ruleset-version aware, so noise ≠ regressions

**Beyond the differential — gated in CI too:**
- Dependency parity vs `mvn dependency:tree` and real Gradle (Groovy + Kotlin-DSL, Gradle 7.6 & 9)
- Binary WAR/EAR/JAR analysis end-to-end; gRPC contract + error-path behaviour
- Soak / leak (long-lived server), resilience & security, perf **regression gate**

> Correctness **and** robustness are now **continuously enforced in CI**, not one-time claims.

---

## How we keep it honest — the parity ledger

**The tempting shortcut:** edit the test's expected answers to match the new tool.
Then the test always passes — and proves nothing.

**What we do instead:** never change the expected answers. For **every** difference
between the new provider and the old one, we write down three things:

- **what** is different
- **who is right** — *old tool is wrong* · *our bug* · *harmless*
- **why**

That record is the **parity ledger** — one file, kept in version control.
Any difference **without** an entry makes the check **fail** — so nothing slips through
unexplained, and every call we made is there for anyone to review.

---

## Honest trade-off #1 — raw performance (today)

| | Go stack | Native Java |
|---|---:|---:|
| Wall clock | 34s | 64s (Go 1.9×) |
| Provider CPU | 73s | 172s (Go 2.4×) |

Real gap — JDTLS has incremental compilation + caching; we parse fresh. **But:**

- **92% of wall time is rule evaluation** — the provider isn't the bottleneck
- **96% of evaluate calls finish <10ms**; provider-side tuning netted **~0s**
- Dominant cost is **engine-side**: multi-condition OR rules run branches **sequentially**.
  The biggest win — **parallel OR conditions** — is an engine change that **helps both providers**.
- Guarded against *regressions*: a nightly **perf gate** fails the build on an order-of-magnitude slowdown.

---

## Parity gaps — closed

| Gap | Status |
|---|---|
| `ENUM` location | **Done** — indexed + unit-tested |
| BOM-import version resolution | **Done** — recursive + parent-chain; validated vs `mvn dependency:tree` |
| Dependency `pom.xml` line numbers | **Done** — `GetDependencyLocation` RPC tested |
| Gradle dependency resolution | **Done** — Groovy **and Kotlin-DSL**; CI across Gradle 7.6 & 9 |
| Gradle dependency-**source** download | **Done** — the Go feature; version-aware, CI-gated |
| Binary **WAR / EAR / JAR** analysis | **Done** — decompile + index + deps; validated on real apps |
| Default (empty) location narrower than Go | Minor, open |

The confirmed provider bugs are **fixed and regression-tested**; correctness is gated by the differential.

---

## Roadmap — what's left

**Correctness** — ✅ done & **gated** (differential on CLI + Hub, 0 regressions)

**Hardening** — ✅ done & **gated**
- Soak / leak (long-lived server), resilience & security, perf **regression gate** — all in CI
- Gradle parity (Groovy + Kotlin-DSL + source download), binary WAR/EAR, matching oracle

**Remaining**
- Finish the compatibility matrix — full-mode & `mvn://` across Java 8/17/21
- Parity-ledger **sign-off** (a review step, not new code)
- **Parallel OR-condition evaluation** in analyzer-lsp — biggest perf lever, **helps all providers**

> Correctness *and* hardening are settled; what's left is matrix breadth + a shared engine win.

---

<!-- _class: lead -->

## The ask

Adopt the native Java provider as the **go-forward Java analysis path**.

Correctness is **proven and continuously gated** — differential vs koncur `main`, on CLI **and** Tackle Hub, **0 regressions**. Non-functional **hardening is also done and gated** (soak, resilience, perf-regression), and **Maven + Gradle (Groovy/Kotlin-DSL) + binary** parity is covered.

Remaining work is **matrix breadth** + the engine-side **parallel-OR** change — biggest perf win, helps everyone.

<br>

**One language · one repo · one process · matching we own and can prove.**

---

## Appendix — benchmark details

- **Provider CPU:** Go 72.7s (JDTLS 65.1s + Go binary 6.0s) vs Java 172.3s
- **Memory timeline:** Go peaks when JDTLS ramps (~1,331 MB @ t=18s); Java is two-phase
  (index ~268 MB → dep-source peak 1,293 MB @ t=53s)
- **Pre/post optimization:** wall 70s → 64s (−9%); provider CPU unchanged (parsing is 2–3% of runtime)

**Why provider-side query tuning didn't help**
- Hash-based exact lookup: indexing ~30k symbols cost ~5s, offsetting savings → reverted
- Evaluate result caching: unique `conditionInfo` per rule → ~0 hits → reverted
- Provider query time (<10ms for 96% of calls) is already below the engine's inter-call time

<!--
Full per-location matching parity table lives in LOCATION_DIFFERENCES.md.
-->

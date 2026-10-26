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

---

## Honest trade-off #2 — parity gaps

| Gap | Impact | Status |
|---|---|---|
| `ENUM` location unimplemented | `location: ENUM` returns nothing | Add `visit(EnumConstantDeclaration)` |
| Default location narrower than Go | Misses method/ctor calls w/o `location:` | Widen default search |
| BOM-import version resolution | Transitive deps missed with BOM imports | Implement in `findManagedVersion` |
| Dep `pom.xml` line numbers = 0 | Wrong incident lines on static fallback | Fix applied, pending CI |

**Scorecard:** ~18 real bugs on our side (both with known fixes) vs **~58 "failures" that were Go being wrong.**

---

## Roadmap to close the gap

**Correctness / parity (short)**
- Implement `ENUM`, widen default location, BOM import resolution, validate line-number fix in CI

**Performance (medium)**
- Engine: **parallel OR-condition evaluation** — biggest lever, helps all providers
- Provider: optional **batch evaluate** / streaming API to cut per-call overhead
- Startup: persistent / incremental index to narrow the cold-parse gap

**Launch tuning**
- `jvmArgs` / `launchArgs` passthrough in provider config (ZGC already wired via Dockerfile)

> None of this reworks the core architecture — it's incremental, some lands in the shared engine.

---

<!-- _class: lead -->

## The ask

Adopt the native Java provider as the **go-forward Java analysis path** — opt-in while we close the enumerated gaps.

Prioritize the **engine-side parallel-OR change** — biggest perf win, helps everyone.

Treat the **koncur parity suite** as the correctness gate.

<br>

**One language · one repo · one process · matching we own and can prove — with a short, concrete gap list.**

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

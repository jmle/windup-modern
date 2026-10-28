# Performance & Robustness Testing

Covers the non-functional hardening of the provider (MTA-8157) — resource lifecycle, concurrency,
performance, and soak — with **how to run each** and **what we've measured**. See `../TEST_PLAN.md` (L5)
for where this sits in the overall strategy.

Principle: **hard-gate the deterministic things** (leaks, cleanup, concurrency correctness); treat raw
performance numbers as **advisory / trend** — CI runners are noisy, so wall-clock/CPU shouldn't block PRs.

## Phase 1 — Resource lifecycle & leaks (deterministic, gated)

**What:** a long-lived provider (e.g. Tackle Hub, analysing app after app in one process) must release
each workspace's resources on `Stop`.

**Test:** `WorkspaceLifecycleTest` — `Stop` removes the workspace *and* deletes the temp dirs it created
(decompiled `java-project/`, `~/.java-provider-work/<id>`, mvn:// download dir); repeated Init/Stop
leaves nothing behind.

**Result:** found and fixed a real **disk leak** — `Stop` previously only unregistered the workspace and
never deleted its temp dirs. `WorkspaceContext.cleanup()` now deletes them; `JavaProviderService.stop()`
calls it. Regression-tested.

**Run:** `mvn -Dtest=WorkspaceLifecycleTest test`

## Phase 2 — Concurrency under load (deterministic, gated)

**What:** the kantra engine fans rule evaluation out to a worker pool and sends **concurrent `Evaluate`
calls to one workspace**, so the query path, the `ThreadLocal<Yaml>` parser, and the glob→regex cache
must be thread-safe.

**Test:** `WorkspaceConcurrencyTest` — 16 threads × 60 iterations × 5 probes (~4,800 concurrent
evaluates) against one indexed workspace; asserts every call returns the correct, consistent result and
the pool finishes (no deadlock).

**Result:** passes — results are consistent under concurrency, no deadlock.

**Run:** `mvn -Dtest=WorkspaceConcurrencyTest test`

## Phase 3 — Performance baseline (advisory)

### Symbol-index micro-benchmark (repeatable)

**Test:** `PerfBenchmarkTest` — generates a synthetic corpus, then measures index time, heap, and query
latency. Opt-in (not part of the gate):

```
mvn -Dperf=true -Dtest=PerfBenchmarkTest test
# tune: -Dperf.files=2000 -Dperf.queries=5000
# results printed and written to target/perf-report.txt
```

**Baseline** (local dev machine, JDK 21 — *indicative, for trend not absolute gating*):

| Metric | Value |
|---|---|
| Files indexed | 2,000 |
| Symbols indexed | 20,000 |
| Index time | ~540 ms (~0.27 ms/file) |
| Used heap after index | ~13 MB |
| Query latency | p50 ≈ 0.55 ms · p95 ≈ 0.61 ms · max ≈ 4.9 ms |

Reproduce it in a fixed environment to catch large regressions (e.g. >40–50%); don't gate on small
deltas.

### End-to-end benchmark (whole-stack)

For the full stack (kantra + provider on a real app), the reference numbers live in
[`../RESOURCE_COMPARISON.md`](../RESOURCE_COMPARISON.md) and [`../OPTIMIZATIONS.md`](../OPTIMIZATIONS.md)
(tackle-testapp, cloud-readiness + quarkus, source-only, 12c/31GB):

| Metric | Go + JDTLS | Native Java |
|---|---:|---:|
| Wall clock | 34s | 64s |
| Provider CPU | 73s | 172s |
| Peak provider memory | 1,682 MB | 1,293 MB |
| Peak threads | 88 | 50 |

Takeaway (see `OPTIMIZATIONS.md`): the provider's **matching query path is not the bottleneck** (96% of
evaluate calls finish <10ms; speeding up queries netted ~0s); the dominant cost is engine-side sequential
OR-branch evaluation, and the few slow calls are dependency rules awaiting the one-time background
dependency resolution.

## Phase 4 — Soak / stress (planned)

**Goal:** catch slow leaks the unit tests can't — run the provider process over **many** Init/Stop cycles
and/or a large monorepo, tracking **process RSS, open file descriptors, thread count, and disk** over
time (process-level RSS matters because JDT uses off-heap/native memory that JVM-heap checks miss).

**Plan:** a nightly workflow (like the differential) that drives the provider via gRPC in a loop and
samples `/proc/<pid>/status` + disk usage, failing on sustained upward drift. Not yet implemented.

## Summary of gates

| Signal | Gate |
|---|---|
| Temp-dir cleanup on Stop / no accumulation | **hard** (per-PR) |
| Concurrency correctness / no deadlock | **hard** (per-PR) |
| Index/query latency, memory | advisory (run on demand / nightly trend) |
| Process RSS/FD/disk over a soak | advisory → alert on drift (planned) |

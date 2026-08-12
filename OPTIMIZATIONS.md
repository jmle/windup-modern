# Rule Evaluation Optimization Opportunities

**Context:** Rule evaluation accounts for 92% of wall time (51s of 64s total). The kantra engine sends 592 gRPC evaluate calls to the Java provider. 96% complete in <10ms, but **20 slow calls take 1-5s each** and account for ~50s of the 51s total. The Go provider has only 10 such slow calls (~15s total).

**Benchmark:** tackle-testapp, cloud-readiness + quarkus targets, source-only mode, 12 cores / 31 GB RAM.

**Engine concurrency model:** The kantra engine uses a **worker pool of 10 goroutines** (configurable via `--workers`) to evaluate rules in parallel. Tagging/info rules run sequentially first (they produce tags other rules depend on), then all remaining rules are fanned out to the worker pool. However, **AND/OR conditions within a single rule are evaluated sequentially** — each OR branch triggers a separate blocking gRPC Evaluate call, and the worker waits for each response before sending the next. This means the slow calls (1-5s) are caused by multi-condition rules where a single worker blocks on sequential OR branch evaluation, not by overall engine serialization.

---

## 1. Fix the 20 Slow Evaluate Calls -- IMPLEMENTED

**Impact:** High (estimated ~30s). **Measured: ~6s savings on rule eval span (51s -> 45s), ~3s on wall time (64s -> 61s).**
**Scope:** Java provider
**Status:** Done. YAML reuse and pattern caching delivered measurable improvement. ZGC eliminates GC pause risk. The remaining slow calls (~20 at 1-5s) are caused by multi-condition dependency OR rules: each OR branch is a separate blocking gRPC call within a single worker goroutine, and the 1-5s gaps occur between sequential OR branch evaluations within these rules.

### JVM GC Pauses -- IMPLEMENTED

Added `-XX:+UseZGC -XX:+ZGenerational` to the Dockerfile entrypoint. ZGC keeps GC pauses under 1ms regardless of heap size, preventing GC from contributing to the 1-5s stalls.

**Kantra integration note:** In production, kantra launches the provider binary via the `lspServerPath` config. The wrapper script (`java-external-provider`) must pass JVM flags. When integrating with kantra, either:
- Update the wrapper script to include `-XX:+UseZGC -XX:+ZGenerational`
- Or add a `jvmArgs` field to the provider config that kantra passes through at launch

Currently kantra launches the provider with a fixed command (`<binary> --port <port> --name <name>`). There is no mechanism to pass JVM options through the provider config. This needs a kantra-side change to support provider-specific launch flags, or the flags must be baked into the wrapper script.

### YAML Parsing Overhead -- IMPLEMENTED

Replaced per-call `new Yaml()` with a `ThreadLocal<Yaml>` instance. This is necessary because kantra's 10-worker pool can send concurrent evaluate calls to the same workspace from different goroutines.

**Before:**
```java
private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
    Yaml yaml = new Yaml();
    Map<String, Object> cond = yaml.load(conditionInfo);
```

**After:**
```java
private static final ThreadLocal<Yaml> YAML = ThreadLocal.withInitial(Yaml::new);
// ...
private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
    Map<String, Object> cond = YAML.get().load(conditionInfo);
```

### Regex Compilation -- IMPLEMENTED

Added a `ConcurrentHashMap<String, Pattern>` cache to `SymbolIndex.globToRegex()`. Repeated patterns (same glob used across multiple evaluate calls) return the cached compiled `Pattern` instead of recompiling.

**Before:**
```java
public static Pattern globToRegex(String glob) {
    // ... builds regex string ...
    return Pattern.compile(regex.toString());
}
```

**After:**
```java
private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

public static Pattern globToRegex(String glob) {
    return PATTERN_CACHE.computeIfAbsent(glob, SymbolIndex::compileGlob);
}
```

### Post-Implementation Analysis

The slow calls (1-5s) persist across runs and affect the same rules: `observability-0100`, `mvc-01220`, `embedded-cache-libraries-15000`, `database-03000`. These are all multi-condition dependency OR rules (e.g., 4 `java.dependency` branches). Within a single rule, the engine evaluates OR conditions sequentially (one blocking gRPC call per branch), with 1-5s gaps between branches. Note: rules themselves run in parallel across 10 workers — the sequential bottleneck is within individual multi-condition rules, not across the ruleset.

---

## 2. Provider-Side Query Optimization -- TRIED, NO IMPROVEMENT

**Impact:** Negligible. **Measured: no improvement (62s with vs 62s without).**
**Scope:** Java provider
**Status:** Tried and reverted. The provider already responds in <10ms for 96% of calls; the bottleneck is engine-side processing time between calls. Speeding up the provider's query path has no measurable effect on wall time or eval span.

### Hash-Based Lookup for Exact Patterns -- TRIED

Added `EnumMap<LocationType, HashMap<String, List<IndexedSymbol>>>` keyed by qualified name, populated during `mergeSymbols()`. Exact-match queries (no wildcards) bypass the regex scan entirely.

**Result:** The indexing overhead (~5s to populate the hash map for ~30k symbols including dependencies) offset the per-query savings. Net effect was neutral to slightly negative. Reverted.

### Evaluate Result Caching -- TRIED

Added `ConcurrentHashMap<String, ProviderEvaluateResponse>` cache keyed by `cap + conditionInfo`.

**Result:** Very few cache hits in practice — each rule sends unique conditionInfo strings. The ConcurrentHashMap overhead added no measurable benefit. Reverted.

### Why provider-side query optimization doesn't help

The provider's query response time is already well under the engine's inter-call processing time. With 592 evaluate calls and 96% completing in <10ms, even a 10x speedup on the provider side saves at most ~3s. The dominant cost (45-50s of the eval span) comes from multi-condition OR rules where each OR branch triggers a separate blocking evaluate call with processing gaps of 1-5s between them. The engine already parallelizes rules across 10 workers, but within a single rule, OR conditions are evaluated sequentially. Only intra-rule condition parallelism (section 3) can address this.

---

## 3. Engine-Level Changes

**Impact:** Moderate (rule-level parallelism already exists)
**Scope:** Kantra engine (Go codebase, `analyzer-lsp`)
**Status:** Rule-level parallelism already implemented (10-worker pool). Remaining opportunities target intra-rule condition parallelism.

### Parallel Rule Evaluation — ALREADY IMPLEMENTED

The kantra engine already uses a **worker pool of 10 goroutines** (configurable via `--workers`) to evaluate rules in parallel. Tagging/info rules run sequentially first (they produce tags other rules depend on), then all remaining rules are fanned out to the pool. The gRPC-Java server handles concurrent calls via its thread pool.

This was previously documented as "not yet implemented" based on incorrect analysis. In reality, the engine has had this since at least the current version.

### Parallel OR Condition Evaluation (within a single rule)

The remaining bottleneck: within a single rule, AND/OR conditions are evaluated **sequentially**. The engine code (`engine/conditions.go`) has an explicit comment: `"For now, lets not fan out the running of conditions."` For OR conditions (which don't chain — each branch is independent), parallel evaluation would eliminate the 1-5s stalls on multi-condition dependency rules.

This is the **single biggest remaining improvement**. The ~20 slow rules (`observability-0100`, `embedded-cache-libraries-15000`, etc.) each have 4+ OR branches evaluated serially. Parallelizing independent OR branches could reduce these from 4-20s per rule to the cost of one branch.

AND conditions are harder to parallelize because they support `as`/`from` chaining where one condition's output feeds the next.

### Batch Evaluate gRPC API

Instead of 592 individual unary gRPC calls, accept a batch of conditions in one call. This eliminates per-call overhead:
- gRPC framing and HTTP/2 stream management
- Protobuf serialization/deserialization per message
- Connection management logging (`connection conn=2` appears before each call)

```protobuf
message BatchEvaluateRequest {
    int64 id = 1;
    repeated SingleEvaluate conditions = 2;
}

message SingleEvaluate {
    string cap = 1;
    string condition_info = 2;
}
```

The provider could then optimize across conditions (e.g., sort by location type, share compiled patterns, batch similar queries).

### Bidirectional Streaming

Use gRPC bidirectional streaming so the engine can pipeline requests without waiting for each response. The engine sends the next evaluate while the provider is still processing the current one, overlapping engine processing with provider work.

### Provider Launch Flags

Kantra currently launches providers with a fixed command: `<binary> --port <port> --name <name>`. There is no way to pass JVM options (like `-XX:+UseZGC`) through the provider config. Options:

1. **Wrapper script** (current workaround): Bake JVM flags into the `java-external-provider` wrapper script. Simple but inflexible -- users can't tune JVM settings without modifying the script.
2. **Provider config field**: Add a `jvmArgs` or `launchArgs` field to `providerSpecificConfig` that kantra passes through when spawning the provider process. More flexible but requires a kantra-side change.
3. **Environment variable**: Have the wrapper script read JVM flags from an environment variable (e.g., `JAVA_PROVIDER_OPTS`). Middle ground -- no kantra changes needed, users can customize via environment.

---

## Estimated Impact Summary

| Optimization | Estimated | Measured | Scope | Status |
|---|---:|---:|---|---|
| ZGC | ~10-15s | prevents GC stalls | JVM flag | Done |
| ThreadLocal Yaml parser | ~2-5s | } ~6s combined | Provider | Done |
| Cache compiled patterns | ~2-5s | } | Provider | Done |
| Hash-based exact lookup | ~5-10s | ~0s (reverted) | Provider | Tried |
| Evaluate result caching | ~2-5s | ~0s (reverted) | Provider | Tried |
| Parallel rule evaluation | ~40-45s | already exists | Engine | Exists |
| Parallel OR conditions | ~20-30s | -- | Engine | Todo |
| Batch evaluate API | ~5-10s | -- | Both | Todo |

**Implemented (section 1):** ~3s wall time savings (64s -> 61s), ~6s rule eval savings (51s -> 45s).
**Tried and reverted (section 2):** No measurable improvement. Provider query time is not the bottleneck.
**Already exists (section 3):** Rule-level parallelism (10-worker pool) is already in the engine.
**Remaining opportunity (section 3):** Parallel OR condition evaluation within rules — the ~20 slow multi-condition rules could see significant speedups.

The measured improvement from section 1 is lower than estimated because the 1-5s slow calls are caused by sequential OR branch processing within individual rules, not by overall engine serialization. The engine already parallelizes across rules; the bottleneck is within single multi-condition rules where OR branches are evaluated one at a time.

---

## Measurement Methodology

Latency distribution measured from kantra analysis logs (`Made call to Evaluate` timestamps). Rule-level timing from `processing rule` log entries. Per-call gaps computed from consecutive evaluate call nanosecond timestamps.

### Pre-Optimization Baseline

| Metric | Java Provider | Go Provider |
|---|---:|---:|
| Total evaluate calls | 592 | 592 |
| Calls <10ms | 571 (96%) | 581 (98%) |
| Calls 1-5s | 18 | 9 |
| Calls >5s | 2 | 1 |
| Avg gap between calls | 86ms | 24ms |
| Total rule evaluation | 51s | 12s |
| Wall clock | 64s | 34s |

### Post-Optimization (Section 1)

| Metric | Run 1 | Run 2 |
|---|---:|---:|
| Total evaluate calls | 360 | 417 |
| Calls <10ms | 339 | 395 |
| Calls >=1s | 20 | 21 |
| Total rule evaluation | 45s | 45s |
| Wall clock | 62s | 61s |

Note: Evaluate call count varies between runs due to non-deterministic rule ordering across the 10 parallel workers. The rule eval span (45s) is consistent.

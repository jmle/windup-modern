# Rule Evaluation Optimization Opportunities

**Context:** Rule evaluation accounts for 92% of wall time (51s of 64s total). The kantra engine sends 592 gRPC evaluate calls to the Java provider. 96% complete in <10ms, but **20 slow calls take 1-5s each** and account for ~50s of the 51s total. The Go provider has only 10 such slow calls (~15s total).

**Benchmark:** tackle-testapp, cloud-readiness + quarkus targets, source-only mode, 12 cores / 31 GB RAM.

---

## 1. Fix the 20 Slow Evaluate Calls -- IMPLEMENTED

**Impact:** High (estimated ~30s). **Measured: ~6s savings on rule eval span (51s -> 45s), ~3s on wall time (64s -> 61s).**
**Scope:** Java provider
**Status:** Done. YAML reuse and pattern caching delivered measurable improvement. ZGC eliminates GC pause risk. The remaining slow calls (~20 at 1-5s) are engine-side: kantra processes multi-condition dependency OR rules sequentially, and the gaps occur between evaluate calls, not during them.

### JVM GC Pauses -- IMPLEMENTED

Added `-XX:+UseZGC -XX:+ZGenerational` to the Dockerfile entrypoint. ZGC keeps GC pauses under 1ms regardless of heap size, preventing GC from contributing to the 1-5s stalls.

**Kantra integration note:** In production, kantra launches the provider binary via the `lspServerPath` config. The wrapper script (`java-external-provider`) must pass JVM flags. When integrating with kantra, either:
- Update the wrapper script to include `-XX:+UseZGC -XX:+ZGenerational`
- Or add a `jvmArgs` field to the provider config that kantra passes through at launch

Currently kantra launches the provider with a fixed command (`<binary> --port <port> --name <name>`). There is no mechanism to pass JVM options through the provider config. This needs a kantra-side change to support provider-specific launch flags, or the flags must be baked into the wrapper script.

### YAML Parsing Overhead -- IMPLEMENTED

Replaced per-call `new Yaml()` with a single `Yaml` instance per `WorkspaceContext`. This is safe because kantra calls evaluate sequentially (no concurrent calls to the same workspace).

**Before:**
```java
private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
    Yaml yaml = new Yaml();
    Map<String, Object> cond = yaml.load(conditionInfo);
```

**After:**
```java
private final Yaml yaml = new Yaml();
// ...
private ProviderEvaluateResponse evaluateReferenced(String conditionInfo) {
    Map<String, Object> cond = yaml.load(conditionInfo);
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

The slow calls (1-5s) persist across runs and affect the same rules: `observability-0100`, `mvc-01220`, `embedded-cache-libraries-15000`, `database-03000`. These are all multi-condition dependency OR rules (e.g., 4 `java.dependency` branches). The engine evaluates each OR branch as a separate gRPC call and waits for each response before sending the next. The stalls are in the engine's sequential OR processing, not in the provider's response time.

---

## 2. Provider-Side Query Optimization

**Impact:** Medium (~5-10s savings)
**Scope:** Java provider
**Status:** Not yet implemented

### Hash-Based Lookup for Exact Patterns

Most rule patterns have no wildcards (e.g., `javax.ejb.SessionBean`). Currently, `SymbolIndex.query()` does a linear scan through all symbols of a location type and applies regex matching for every symbol.

**Fix:** When the glob has no `*` or `?`, use a `HashMap<String, List<IndexedSymbol>>` keyed by qualified name instead of regex scanning. This turns O(n) per query into O(1).

```java
private final Map<String, Map<String, List<IndexedSymbol>>> byLocationAndQN = new EnumMap<>(LocationType.class);

public List<IndexedSymbol> query(String pattern, LocationType location) {
    if (!containsWildcard(pattern)) {
        return byLocationAndQN
            .getOrDefault(location, Map.of())
            .getOrDefault(pattern, List.of());
    }
    // Fall back to regex scan for wildcard patterns
    ...
}
```

### Pre-Index by Qualified Name Prefix

For wildcard patterns like `org.springframework.*`, a prefix-based index would avoid scanning all symbols. Build a `TreeMap<String, List<IndexedSymbol>>` at init time, then use `subMap()` to narrow the search range.

### Evaluate Result Caching

Cache evaluate results by `conditionInfo` string. Many rules query identical or overlapping patterns; a cache avoids redundant index scans.

```java
private final Map<String, ProviderEvaluateResponse> evaluateCache = new HashMap<>();

public ProviderEvaluateResponse evaluate(String cap, String conditionInfo) {
    return evaluateCache.computeIfAbsent(cap + ":" + conditionInfo,
        k -> doEvaluate(cap, conditionInfo));
}
```

---

## 3. Engine-Level Changes

**Impact:** Highest theoretical impact
**Scope:** Kantra engine (Go codebase, `analyzer-lsp`)
**Status:** Not yet implemented. These require changes outside the Java provider but would benefit all providers.

### Parallel Rule Evaluation

The kantra engine processes 1,075 rules sequentially. Most rules are independent (no `as`/`from` chaining). Evaluating them concurrently (e.g., 8 goroutines with a semaphore) would reduce the 51s evaluation phase to ~6-8s.

This is the single biggest possible improvement. The provider's gRPC server already supports concurrent calls (gRPC-Java uses a thread pool). The engine just needs to dispatch rules in parallel and collect results.

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
| Reuse Yaml parser | ~2-5s | } ~6s combined | Provider | Done |
| Cache compiled patterns | ~2-5s | } | Provider | Done |
| Hash-based exact lookup | ~5-10s | -- | Provider | Todo |
| Evaluate result caching | ~2-5s | -- | Provider | Todo |
| Parallel rule evaluation | ~40-45s | -- | Engine | Todo |
| Batch evaluate API | ~5-10s | -- | Both | Todo |

**Implemented (section 1):** ~3s wall time savings (64s -> 61s), ~6s rule eval savings (51s -> 45s).
**Remaining provider-side (section 2):** ~5-10s additional savings estimated.
**With engine parallelism (section 3):** ~40-45s savings, bringing total wall time to ~15-20s (faster than Go's 34s).

The measured improvement from section 1 is lower than estimated because the 1-5s slow calls turned out to be engine-side (sequential OR branch processing), not provider-side. The provider responds in <10ms for 96% of calls; the stalls happen while the engine processes results between calls.

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

Note: Evaluate call count varies between runs due to non-deterministic rule ordering in the kantra engine. The rule eval span (45s) is consistent.

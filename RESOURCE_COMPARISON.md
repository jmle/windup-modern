# Resource Usage Comparison: Go Provider Stack vs Java Provider (Optimized)

**System:** 12 cores, 31 GB RAM, Linux 6.18, OpenJDK 21
**App:** tackle-testapp | **Targets:** cloud-readiness + quarkus | **Mode:** source-only
**Date:** 2026-08-12
**Java Provider Optimizations:** Parallel file parsing (ForkJoinPool), ArrayList (no CopyOnWriteArrayList), JDT batch API (FileASTRequestor)

## Architecture

| | Go Provider Stack | Java Provider |
|---|---|---|
| Processes | kantra + `java-external-provider` (Go binary) + JDTLS (Java) + Gradle (Java) | kantra + `java-provider.jar` (Java) + Gradle (Java) |
| Provider design | Thin Go gRPC client -> JDTLS language server | Embedded JDT Core in single JVM |

## Wall Clock Time

| | Go Stack | Java Provider | Ratio |
|---|---:|---:|---|
| **Wall clock** | **34s** | **64s** | Go 1.9x faster |

## CPU Time (cumulative, all processes)

| Process | Go Stack | Java Provider |
|---|---:|---:|
| kantra engine | 13.7s | 12.8s |
| Go provider binary | 6.0s | -- |
| JDTLS | 65.1s | -- |
| Java provider JAR | -- | 171.1s |
| Gradle | ~1.6s | ~1.2s |
| **Provider total** | **72.7s** | **172.3s** |
| **Full total** | **86.4s** | **185.1s** |

The Java provider consumes **2.4x more CPU time** (172s vs 73s) despite doing the same work. The Go+JDTLS stack is more CPU-efficient because JDTLS is a mature, optimized language server with incremental compilation and caching.

## Memory (RSS)

| Process | Go Stack Peak | Java Provider Peak |
|---|---:|---:|
| kantra engine | 74 MB | 74 MB |
| Go provider binary | 351 MB | -- |
| JDTLS | 1,331 MB | -- |
| Java provider JAR | -- | 1,293 MB |
| **Provider total** | **1,682 MB** | **1,293 MB** |

The Go stack uses **30% more memory** (1,682 MB vs 1,293 MB) because it runs two separate processes (Go binary at 351 MB + JDTLS at 1,331 MB). The Java provider consolidates everything into one JVM at 1,293 MB.

Note: peak RSS for Go stack processes may not coincide. Concurrent peak was ~1,603 MB (at t=18s: Go binary 272 MB + JDTLS 1,331 MB).

## Memory Timeline

### Go stack -- JDTLS dominates after ~8s

```
 0s  provider starting (0 MB)
 4s  Go binary loading (19 MB), no JDTLS yet
 6s  Go binary loaded (189 MB)
 8s  JDTLS starting (94 MB), Go binary stable (270 MB)
14s  JDTLS ramping (267 MB, 58 threads)
18s  JDTLS peak (1,331 MB), total = 1,603 MB
24s  JDTLS settling (1,243 MB)
32s  Go binary grown (351 MB), JDTLS stable (1,298 MB)
34s  Done
```

### Java provider -- two-phase memory growth

```
 0s  JVM starting (0 MB)
 2s  JVM loaded (94 MB, 30 threads)
 7s  Indexing phase (265 MB, 49 threads)
 9s  Indexing done, stable plateau (268 MB)
11s  Dependencies resolving (510 MB)
13s  Dependencies loaded (591 MB), stable
44s  Dependency source resolution begins (593 MB)
47s  Source JARs loading (1,094 MB)
53s  Peak memory (1,293 MB)
64s  Done
```

## Threads

| | Go Stack Peak | Java Provider Peak |
|---|---:|---:|
| Go provider binary | 14 | -- |
| JDTLS | 74 | -- |
| Java provider JAR | -- | 50 |
| **Provider total** | **88** | **50** |

## Deployment Footprint

| Component | Go Stack | Java Provider |
|---|---:|---:|
| Provider binary/JAR | 15 MB | 36 MB |
| JDTLS language server | 53 MB | not needed |
| Decompiler (fernflower) | 0.7 MB | not needed |
| **Provider-specific total** | **69 MB** | **36 MB** |

The Java provider's deployment is **47% smaller** since it embeds JDT Core and doesn't need the external JDTLS or fernflower.

## Optimization Impact (vs pre-optimization baseline)

| Metric | Pre-optimization | Post-optimization | Change |
|---|---:|---:|---|
| Wall clock | 70s | 64s | **-9%** |
| Provider CPU | 171s | 171s | ~0% |
| Peak provider RSS | 1,119 MB | 1,293 MB | +15% |
| Peak threads | 50 | 50 | ~0% |

The parallel parsing optimizations reduced wall time by ~6s (init/indexing from ~4s to ~2s). CPU time is unchanged because the parsing phase is only 2-3% of total runtime -- the kantra engine's sequential rule evaluation (1,075 rules via gRPC) dominates at 92% of wall time. The memory increase comes from ForkJoinPool allocating multiple parser instances concurrently.

## Summary

| Dimension | Go Stack | Java Provider | Winner |
|---|---:|---:|---|
| Wall clock | 34s | 64s | **Go** (1.9x) |
| Total CPU time | 86s | 185s | **Go** (2.1x) |
| Provider CPU time | 73s | 172s | **Go** (2.4x) |
| Peak provider memory | 1,682 MB | 1,293 MB | **Java** (23% less) |
| Peak threads | 88 | 50 | **Java** (43% fewer) |
| Process count | 3 | 1 | **Java** (simpler) |
| Deployment size | 69 MB | 36 MB | **Java** (48% smaller) |
| Stability | Crashes without JAVA8_HOME | Clean runs | **Java** |

The Go+JDTLS stack is significantly faster in wall-clock and CPU time -- JDTLS is a highly optimized language server. The Java provider wins on memory efficiency, architectural simplicity (single process), deployment size, and stability. The CPU efficiency gap is the main area for optimization in the Java provider, but most of the wall time (92%) is spent in the kantra engine's rule evaluation, not in the provider itself.

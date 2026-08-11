# Resource Usage Comparison: Go Provider Stack vs Java Provider

**System:** 12 cores, 31 GB RAM, Linux 6.18, OpenJDK 21
**App:** tackle-testapp | **Targets:** cloud-readiness + quarkus | **Mode:** source-only
**Date:** 2026-08-11

## Architecture

| | Go Provider Stack | Java Provider |
|---|---|---|
| Processes | kantra + `java-external-provider` (Go binary) + JDTLS (Java) + Gradle (Java) | kantra + `java-provider.jar` (Java) + Gradle (Java) |
| Provider design | Thin Go gRPC client → JDTLS language server | Embedded JDT Core in single JVM |

## Wall Clock Time

| | Go Stack | Java Provider | Ratio |
|---|---:|---:|---|
| **Wall clock** | **34.4s** | **70.2s** | Go 2x faster |

## CPU Time (cumulative, all processes)

| Process | Go Stack | Java Provider |
|---|---:|---:|
| kantra engine | 13.8s | 11.6s |
| Go provider binary | 6.2s | -- |
| JDTLS | 57.4s | -- |
| Java provider JAR | -- | 169.6s |
| Gradle | 1.3s | 1.2s |
| **Provider total** | **64.9s** | **170.8s** |
| **Full total** | **78.7s** | **182.4s** |

The Java provider consumes **2.6x more CPU time** (170.8s vs 64.9s) despite doing the same work. The Go+JDTLS stack is more CPU-efficient because JDTLS is a mature, optimized language server with incremental compilation and caching.

## Memory (RSS)

| Process | Go Stack Peak | Java Provider Peak |
|---|---:|---:|
| kantra engine | 78 MB | 73 MB |
| Go provider binary | 333 MB | -- |
| JDTLS | 1,112 MB | -- |
| Java provider JAR | -- | 1,119 MB |
| **Provider total** | **1,445 MB** | **1,119 MB** |

The Go stack uses **29% more memory** (1,445 MB vs 1,119 MB) because it runs two separate processes (Go binary at 333 MB + JDTLS at 1,112 MB). The Java provider consolidates everything into one JVM at 1,119 MB.

## Memory Timeline

### Go stack — fast ramp-up, JDTLS dominates after ~11s

```
 0s  provider starting (14 MB)
 5s  Go binary loaded (285 MB), no JDTLS yet
11s  JDTLS starting (137 MB), Go binary stable (286 MB)
16s  JDTLS loaded (963 MB), total = 1,254 MB
28s  JDTLS peak (1,103 MB), total peak = 1,445 MB
30s  Done
```

### Java provider — steady climb, two phases

```
 0s  JVM starting (94 MB)
 7s  Indexing phase (265 MB)
11s  Dependencies resolved (701 MB), stable plateau
44s  Dependency source resolution (953 MB → 1,119 MB)
58s  Done
```

## Threads

| | Go Stack Peak | Java Provider Peak |
|---|---:|---:|
| Go provider binary | 15 | -- |
| JDTLS | 74 | -- |
| Java provider JAR | -- | 50 |
| **Provider total** | **89** | **50** |

## Deployment Footprint

| Component | Go Stack | Java Provider |
|---|---:|---:|
| Provider binary/JAR | 15 MB | 36 MB |
| JDTLS language server | 53 MB | not needed |
| Decompiler (fernflower) | 0.7 MB | not needed |
| **Provider-specific total** | **69 MB** | **36 MB** |

The Java provider's deployment is **47% smaller** since it embeds JDT Core and doesn't need the external JDTLS or fernflower.

## Summary

| Dimension | Go Stack | Java Provider | Winner |
|---|---:|---:|---|
| Wall clock | 34s | 70s | **Go** (2x) |
| Total CPU time | 79s | 182s | **Go** (2.3x) |
| Provider CPU time | 65s | 171s | **Go** (2.6x) |
| Peak provider memory | 1,445 MB | 1,119 MB | **Java** (22% less) |
| Peak threads | 89 | 50 | **Java** (44% fewer) |
| Process count | 3 | 1 | **Java** (simpler) |
| Deployment size | 69 MB | 36 MB | **Java** (48% smaller) |
| Stability | Crashed without JAVA8_HOME | Clean runs | **Java** |

The Go+JDTLS stack is significantly faster in wall-clock and CPU time — JDTLS is a highly optimized language server. The Java provider wins on memory efficiency, architectural simplicity (single process), deployment size, and stability. The CPU efficiency gap is the main area for optimization in the Java provider.

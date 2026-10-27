# Java Analyzer Provider

A **native Java analysis provider for Konveyor**, implementing the `analyzer-lsp`
`ProviderService` gRPC contract. It replaces the Go external provider + JDTLS tandem with a single
JVM that embeds the Java tooling it needs as **libraries**:

- **Eclipse JDT Core** — AST parsing and symbol indexing (no JDTLS language server)
- **Vineflower** — decompilation of binary inputs (JAR/WAR/EAR)
- **Maven Resolver (Aether)** — in-process dependency resolution (no shelling out to `mvn`)

The result is one process instead of three, matching logic we own and can test directly, and a
smaller, more stable footprint. See [`CASE_FOR_JAVA_PROVIDER.md`](CASE_FOR_JAVA_PROVIDER.md) for the
full rationale (and `slides/` for the deck).

## What it does

Speaks the Konveyor `ProviderService` gRPC API (`src/main/proto/library.proto`):

- `Init` / `Stop` — per-workspace lifecycle (directory, binary archive, or `mvn://` input)
- `Evaluate` — the `referenced` (symbol/location matching) and `dependency` capabilities
- `GetDependencies` / `GetDependenciesDAG`, plus code-snippet and dependency-location services

## Build & run

```bash
mvn package                 # builds the shaded fat JAR
java -jar target/*.jar --port=<port>   # entry point: io.konveyor.provider.JavaProviderMain
```

A container image for use with kantra / Tackle Hub is built from `Dockerfile.kantra`.

## Testing & validation

Correctness is established by **differential testing against the reference Go provider's output**,
not by asserting equality blindly. See [`TEST_PLAN.md`](TEST_PLAN.md) and
[`testing/DIFFERENTIAL_HARNESS.md`](testing/DIFFERENTIAL_HARNESS.md).

| Layer | What | When |
|---|---|---|
| **Unit** | JUnit (symbol index, BOM resolution, dependency-location RPC, …) + the triage-harness tests | per PR (`.github/workflows/unit-tests.yaml`) |
| **Differential (CLI)** | Run the provider vs **vanilla koncur `main`**, diff each result against the Go golden, classify via the parity ledger | nightly + manual, **gating** (`koncur-differential.yaml`) |
| **Differential (Hub)** | Same, but through **Tackle Hub in-cluster** (the production path) | nightly + manual, **gating** (`koncur-hub-e2e.yaml`) |

How the differential works (`testing/koncur_triage.py`):

1. Run the new provider against koncur `main` and regenerate per-test output.
2. Diff vs the committed Go golden, with a comparator that is **base-dir-, decompiler-line-, and
   ruleset-version-aware** so environmental noise isn't mistaken for a regression, and that compares
   **Java-provider incidents** (tags are engine-derived and excluded).
3. Classify each difference against [`testing/koncur-parity-ledger.yaml`](testing/koncur-parity-ledger.yaml):
   `go-wrong` / benign (accepted) → **explained**; `java-wrong` → known bug; anything else →
   **fails the gate**.

**Current status:** across all Java sample apps, on both the CLI and Tackle Hub paths, the provider
produces **0 unexplained differences and 0 regressions** — every difference is a documented Go-provider
bug (over-/under-matching) or benign. Remaining work is non-functional hardening (performance, soak /
memory-leak, resilience); see `TEST_PLAN.md` exit criteria.

## Repository docs

| Doc | Purpose |
|---|---|
| `CASE_FOR_JAVA_PROVIDER.md` + `slides/` | The case for switching (written + deck) |
| `TEST_PLAN.md` | Layered testing strategy + exit criteria |
| `testing/DIFFERENTIAL_HARNESS.md` | How the differential gate works |
| `testing/koncur-parity-ledger.yaml` | Tracked record of every known provider-vs-Go difference |
| `LOCATION_DIFFERENCES.md` | Per-`location` matching behavior vs the Go provider |
| `RESOURCE_COMPARISON.md`, `OPTIMIZATIONS.md` | Footprint and performance analysis |
| `FAILING_TESTS.md` | Historical E2E snapshot (superseded by the ledger) |

## Contributing

Commits must be **DCO signed-off** (`git commit -s`); konveyor enforces this.

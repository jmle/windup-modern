# Missing Features: Go Provider vs Java Provider

This document catalogs features present in the Go-based Konveyor Java external provider
(`analyzer-lsp/external-providers/java-external-provider`) that are not yet implemented in
`java-analyzer-provider`. These features are required for production parity.

---

## 1. `depLabelSelector` / Open-Source Library Scope Filtering

**Priority: Low** (engine handles filtering)

The Go provider reads a label selector from the condition context and uses it to decide
whether dependency-sourced symbols should be included in query results. It passes
`includeOpenSourceLibraries` to the JDTLS rule query command. This allows rules to match
only in application code, excluding third-party library incidents.

**Why not implemented:** The kantra engine already performs incident filtering via
`matchDepLabelSelector()` after the provider returns results. The provider-side
implementation is purely a performance optimization to reduce the number of incidents
returned, not a correctness requirement. The engine always filters regardless.

**Current state:** All indexed symbols (application + dependency) are always included in
query results. The engine filters them post-return.

---

## Not Gaps (Architectural Differences, Dead Code, or Stubs)

| Go Provider Feature | Why not a gap |
|---|---|
| JDT Class File URI Resolution (`konveyor-jdt://`) | JDTLS-specific; Java provider uses AST directly |
| `ruleQueryTimeout` | Only relevant to JDTLS approach |
| JDTLS process management / `$/progress` tracking | Architectural difference — Java provider embeds JDT Core |
| `excludePackages` in evaluation flow | Dead code in Go provider (defined but never called) |
| Bytecode scanning (.class analysis) | Not in Go provider either (JDTLS handles internally) |
| `NotifyFileChanges` | Stub in Go provider too (returns nil with TODO comment) |

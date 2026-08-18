# Location Type Differences: Go Provider vs Java Provider

This document maps how each `location` value in Konveyor rule conditions is handled
by the Go provider (JDTLS-based) versus our Java provider (AST-based), and highlights
inconsistencies in both.

## Go Provider Architecture

The Go provider has a three-layer pipeline for each location:

1. **Search pattern** (`SampleDelegateCommandHandler.getPatternSingleQuery`): creates a
   `SearchPattern` using `IJavaSearchConstants` type and limitTo values.
2. **SymbolProvider** (`SymbolProviderResolver`): post-processes each `SearchMatch`,
   casting to expected element types and discarding mismatches.
3. **Go filter function** (`service_client.go`): secondary filtering on the Go side
   before converting symbols to incidents.

Our provider uses a single-layer approach: the `SymbolCollector` AST visitor populates
per-location buckets in `byLocation`, and `SymbolIndex.query()` looks up the
appropriate bucket directly.

## Per-Location Comparison

### Default (empty string / no location specified)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | OR of TYPE/ALL\_OCCURRENCES + METHOD/REFERENCES + CONSTRUCTOR/ALL\_OCCURRENCES | TYPE bucket only |
| Provider | `DefaultSymbolProvider` chains: MethodCall -> ConstructorCall -> Import -> Type (first match wins) | Searches TYPE bucket + IMPORT bucket |
| Filter | `filterDefault` (pass-through) | None |

**Gap**: Our default is significantly narrower. A rule with no `location:` and pattern
`some.Class` will find method invocations like `some.Class.doSomething()` in the Go
provider but miss them in ours. The Go default is designed as a "find any usage"
search.

### TYPE (location string `"type"`, Go int 10)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / ALL\_OCCURRENCES | TYPE bucket |
| Provider | `TypeSymbolProvider` (accepts TypeReferenceMatch, TypeDeclarationMatch, etc.) | Also includes IMPORT bucket matches |
| Filter | `filterDefault` (pass-through) | None |

**Difference**: Our TYPE query also scans the IMPORT bucket and includes matching
import symbols (lines 232-241 of SymbolIndex.java). Go's `TypeSymbolProvider` does
NOT accept `IImportDeclaration` — only the DEFAULT provider chains to
`ImportSymbolProvider`. So our TYPE is broader than Go's TYPE.

### IMPORT (location string `"import"`, Go int 8)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / ALL\_OCCURRENCES (same as TYPE and ANNOTATION) | IMPORT bucket |
| Provider | `ImportSymbolProvider` (casts to `IImportDeclaration`, silently discards everything else) | Direct bucket lookup |
| Filter | `filterModulesImports` (keeps only Kind == Module) | Filters for SymbolKind.MODULE in WorkspaceContext |
| Extra | Post-search AST scan for on-demand imports (`import foo.*`) when query matches `[^A-Z*]+\*` | On-demand imports indexed directly by SymbolCollector |

**Go inconsistency**: IMPORT doesn't search for imports — it searches for TYPE
references and throws away non-imports via a catch block in `ImportSymbolProvider`.
Our approach is cleaner: imports are indexed into their own bucket by the AST visitor.

### ANNOTATION (location string `"annotation"`, Go int 4)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / ALL\_OCCURRENCES (same as TYPE and IMPORT) | ANNOTATION bucket |
| Provider | `AnnotationSymbolProvider` (complex: iterates `IAnnotatable.getAnnotations()`, falls back to AST visitor for FQN-qualified queries) | Direct bucket lookup |
| Filter | `filterDefault` (pass-through) | None |

**Go inconsistency**: ANNOTATION shares the TYPE search. The `AnnotationSymbolProvider`
has fallback paths (AST visitor when query contains `.`) that can match non-annotation
type usages. Example: `javax.ejb.*` at ANNOTATION matches `throw new EJBException(...)`
constructor calls in the Daytrader test (lines 160, 217, 248, etc. of
TradeSLSBBean.java).

**Our behavior**: Our ANNOTATION bucket contains only actual `@Annotation` usages from
the three annotation visitors (`visit(MarkerAnnotation)`, `visit(NormalAnnotation)`,
`visit(SingleMemberAnnotation)`). No leaky fallbacks. More correct but produces fewer
matches than Go in some cases.

**Source-only mode difference**: In source-only mode without library JARs, Go's JDTLS
can't verify that a type is actually an annotation (it needs the class bytes), so
ANNOTATION queries fail to match. Our AST approach resolves the FQN from imports and
matches regardless. This causes false positives vs Go in the Seam booking test
(`seam-java-00040` through `seam-java-00230`).

### PACKAGE (location string `"package"`, Go int 11)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | OR of PACKAGE/DECLARATIONS + PACKAGE/REFERENCES | PACKAGE bucket + derives from IMPORT bucket |
| Provider | `PackageDeclarationSymbolProvider` (handles 5 element types: IImportDeclaration, IType, IMethod, IField, IPackageDeclaration, ICompilationUnit, IPackageFragment) | Matches package declarations + extracts package portion from imports |
| Filter | `filterDefault` (pass-through) | None |

**Overlap with IMPORT (replicated)**: Both providers reach import statements. PACKAGE
extracts the package portion (e.g., `javax.ejb` from `import javax.ejb.SessionBean`),
while IMPORT returns the full FQN. This overlap exists in both Go and our provider —
we replicated this inconsistency in `queryPackage()`.

### INHERITANCE (location string `"inheritance"`, Go int 1)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / IMPLEMENTORS | INHERITANCE bucket |
| Provider | `InheritanceSymbolProvider` (only classes with non-Object superclass or interfaces with super-interfaces) | Direct bucket lookup |
| Filter | `filterTypesInheritance` | None |

**Consistent**: Both find classes/interfaces that extend/implement the pattern. Our
SymbolCollector indexes superclass references at INHERITANCE and interface references
at INHERITANCE (for interface-extends-interface) or IMPLEMENTS\_TYPE (for
class-implements-interface), matching the Go behavior.

### IMPLEMENTS\_TYPE (location string `"implements_type"`, Go int 5)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / IMPLEMENTORS | IMPLEMENTS\_TYPE bucket |
| Provider | `ImplementsTypeSymbolProvider` (filters out interfaces — only classes that implement) | Direct bucket lookup |
| Filter | `filterTypesInheritance` | None |

**Consistent**: Both find classes that implement an interface. Our SymbolCollector
correctly assigns IMPLEMENTS\_TYPE only for class-implements-interface relationships.

### METHOD\_CALL (location string `"method_call"`, Go int 2)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | METHOD / QUALIFIED\_REFERENCE (if query contains `.`) else METHOD / REFERENCES | METHOD\_CALL bucket |
| Provider | `MethodCallSymbolProvider` (AST visitor for FQN validation when query contains `.`) | Direct bucket lookup |
| Filter | `filterMethodSymbols` (currently pass-through, TODO comment) | None |

**Consistent in intent**: Both find method invocations. Our SymbolCollector resolves
the receiver type through imports, field types, and local variable types to build the
FQN for each method call.

### CONSTRUCTOR\_CALL (location string `"constructor_call"`, Go int 3)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | CONSTRUCTOR / ALL\_OCCURRENCES | CONSTRUCTOR\_CALL bucket |
| Provider | `ConstructorCallSymbolProvider` (filters for `isConstructor()`, AST visitor for FQN validation) | Direct bucket lookup |
| Filter | `filterDefault` (pass-through) | None |

**Consistent**: Both find `new Foo()` expressions.

### METHOD (location string `"method"`, Go int 13)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | METHOD / DECLARATIONS | METHOD bucket |
| Provider | `MethodDeclarationSymbolProvider` (accepts MethodDeclarationMatch / IMethod) | Direct bucket lookup |
| Filter | `filterDefault` (pass-through) | None |

**Consistent**: Both find method declarations. Our SymbolCollector also indexes a
signature variant (with type parameters and parameter types) for pattern matching.

### RETURN\_TYPE (location string `"return_type"`, Go int 7)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / REFERENCES | RETURN\_TYPE bucket |
| Provider | `ReturnTypeSymbolProvider` (casts to IMethod, checks return type signature) | Direct bucket lookup |
| Filter | `filterMethodSymbols` (currently pass-through) | None |

**Go inconsistency**: Searches all TYPE references, then narrows to methods whose
return type matches. Our approach is cleaner: return types are indexed directly into
their own bucket.

### FIELD (location string `"field"` or `"field_declaration"`, Go int 12)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / FIELD\_DECLARATION\_TYPE\_REFERENCE | FIELD bucket |
| Provider | `FieldSymbolProvider` (accepts TypeReferenceMatch / ResolvedSourceField) | Direct bucket lookup |
| Filter | `filterDefault` (pass-through) | None |

**Consistent**: Both find field declarations by their type.

### CLASS (location string `"class"`, Go int 14)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | CLASS / DECLARATIONS | CLASS bucket |
| Provider | `ClassDeclarationSymbolProvider` (accepts TypeDeclarationMatch / IType) | Direct bucket lookup |
| Filter | `filterDefault` (pass-through) | None |

**Consistent**: Both find class/interface declarations.

### ENUM (location string `"enum"`, Go int 6)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | FIELD / ALL\_OCCURRENCES | ENUM bucket (empty) |
| Provider | `EnumConstantSymbolProvider` (filters for `IField.isEnumConstant()`) | Not implemented |
| Filter | `filterDefault` (pass-through) | None |

**Go inconsistency**: ENUM searches the FIELD index and filters for enum constants.
**Our gap**: We don't index enum constants at all. `visit(EnumDeclaration)` adds the
enum type at CLASS location, but there is no `visit(EnumConstantDeclaration)`. The
ENUM bucket is always empty.

### VARIABLE\_DECLARATION (location string `"variable_declaration"`, Go int 9)

| Aspect | Go Provider | Our Provider |
|--------|------------|--------------|
| Search | TYPE / REFERENCES | VARIABLE\_DECLARATION bucket |
| Provider | `VariableDeclarationSymbolProvider` (requires TypeReferenceMatch, finds local variables) | Direct bucket lookup |
| Filter | `filterVariableDeclaration` | None |

**Consistent in intent**: Both find local variable declarations by their type.

## Summary of Inconsistencies

### In the Go provider (architectural)

1. **IMPORT, TYPE, ANNOTATION share the same search pattern** (TYPE/ALL\_OCCURRENCES).
   Differentiation is purely post-hoc filtering in the SymbolProvider.
2. **PACKAGE overlaps with IMPORT** — both reach import statements through different
   mechanisms.
3. **ANNOTATION leaks non-annotation matches** — the AnnotationSymbolProvider's AST
   visitor fallback can match constructor calls and other type usages.
4. **ENUM searches FIELD** — searches the field index and filters for enum constants.
5. **RETURN\_TYPE searches TYPE** — searches all type references and filters for return
   type positions.

### Replicated in our provider

| # | Inconsistency | Replicated? | Notes |
|---|--------------|-------------|-------|
| 1 | IMPORT/TYPE/ANNOTATION share search | No | We use separate buckets — cleaner |
| 2 | PACKAGE overlaps with IMPORT | **Yes** | `queryPackage()` derives from IMPORT bucket |
| 3 | ANNOTATION leaks non-annotation matches | No | Our ANNOTATION bucket has only real `@Annotation` usages |
| 4 | ENUM searches FIELD | No | ENUM not implemented at all |
| 5 | RETURN\_TYPE searches TYPE | No | RETURN\_TYPE has its own bucket |

### Unique to our provider

| # | Difference | Impact |
|---|-----------|--------|
| 1 | **Default ("") maps to TYPE only** — Go's default is TYPE + METHOD + CONSTRUCTOR with a multi-provider chain | Rules with no location miss method calls and constructor calls |
| 2 | **TYPE includes IMPORT symbols** — Go's TYPE does not include imports | Our TYPE is broader than Go's; could produce extra matches |
| 3 | **ANNOTATION matches in source-only mode** — Go's JDTLS can't verify annotation types without library JARs | False positives vs Go (Seam booking test) |
| 4 | **ENUM unimplemented** — no `visit(EnumConstantDeclaration)` | Rules using `location: ENUM` return zero matches |

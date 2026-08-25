# Eclipse SearchPattern vs Konveyor Java Provider

Comparison of [Eclipse JDT SearchPattern](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/search/SearchPattern.html) capabilities against our current SymbolIndex/SymbolCollector implementation.

## searchFor — What Kind of Element to Search

### Supported

| Eclipse Constant | Our LocationType | Notes |
|---|---|---|
| TYPE | `@type` | All type references (classes, interfaces, enums, annotations) |
| CLASS | `@class` | Class declarations |
| ENUM | `@enum` | Enum declarations, constants, and references |
| FIELD | `@field` | Field declarations |
| METHOD | `@method` | Method declarations |
| PACKAGE | `@package` | Package declarations |
| CONSTRUCTOR | `@constructor_call` | Constructor calls only, not declarations |

### Not Implemented

| Eclipse Constant | Notes |
|---|---|
| INTERFACE | We treat interfaces as CLASS; no distinct location |
| ANNOTATION_TYPE | We index annotation usage (`@annotation`), not annotation type definitions |
| CONSTRUCTOR (declarations) | We only index constructor *calls*, not the declarations themselves |
| MODULE | Java 9+ module-info.java; unlikely to matter for migration analysis |
| CLASS_AND_ENUM | Combo search; could be done with two queries |
| CLASS_AND_INTERFACE | Combo search; could be done with two queries |

## limitTo — Declarations, References, and Fine-Grained Filters

### Supported

| Eclipse Constant | Our LocationType | Notes |
|---|---|---|
| DECLARATIONS | `@method`, `@class`, `@field` | Element declarations |
| REFERENCES | `@method_call`, `@constructor_call`, `@type` | Element references |
| IMPLEMENTORS | `@implements_type`, `@inheritance` | Types implementing/extending a given type |
| FIELD_DECLARATION_TYPE_REFERENCE | `@field` | Type used in a field declaration |
| RETURN_TYPE_REFERENCE | `@return_type` | Type used as method return type |
| IMPORT_DECLARATION_TYPE_REFERENCE | `@import` | Type referenced in an import |
| ANNOTATION_TYPE_REFERENCE | `@annotation` | Type used as an annotation |
| CLASS_INSTANCE_CREATION_TYPE_REFERENCE | `@constructor_call` | Type used in `new Foo()` |
| SUPERTYPE_TYPE_REFERENCE | `@inheritance` | Type used as superclass/superinterface |
| LOCAL_VARIABLE_DECLARATION_TYPE_REFERENCE | `@variable_declaration` | Type used in local variable declaration |

### Not Implemented

| Eclipse Constant | Notes |
|---|---|
| ALL_OCCURRENCES | Combined declarations + references in a single query |
| PARAMETER_DECLARATION_TYPE_REFERENCE | Types used as method parameter types |
| THROWS_CLAUSE_TYPE_REFERENCE | Types used in `throws` clauses |
| CAST_TYPE_REFERENCE | Types used in cast expressions `(Foo) x` |
| CATCH_TYPE_REFERENCE | Types used in catch block headers |
| INSTANCEOF_TYPE_REFERENCE | Types in `instanceof` expressions (indexed under TYPE but not as distinct location) |
| TYPE_ARGUMENT_TYPE_REFERENCE | Types used as generic type arguments |
| TYPE_VARIABLE_BOUND_TYPE_REFERENCE | Types used as type variable bounds |
| WILDCARD_BOUND_TYPE_REFERENCE | Types used as wildcard bounds |
| PERMITTYPE_TYPE_REFERENCE | Types used in sealed class `permits` clause |
| SUPER_REFERENCE | Field/method accesses qualified with `super` |
| THIS_REFERENCE | Field/method accesses qualified with `this` |
| QUALIFIED_REFERENCE | Qualified field/method accesses |
| IMPLICIT_THIS_REFERENCE | Unqualified field/method accesses |
| METHOD_REFERENCE_EXPRESSION | Method reference expressions `Foo::bar` (Java 8+) |
| MODULE_GRAPH | Module dependency graph search |

## Pattern String Syntax

### Supported

| Feature | Syntax | Example |
|---|---|---|
| Fully qualified name | `pkg.Class` | `javax.ejb.Singleton` |
| Glob wildcard | `*` (0+ chars) | `javax.ejb.*`, `get*` |
| Method + return type | `* ReturnType@method` | `* com.example.Customer@method` |
| Field + type | `* FieldType@field` | `* com.example.Bean@field` |
| Alternation groups | `(A\|B)` | `javax.(ejb\|persistence).*` |

### Not Implemented

| Feature | Eclipse Syntax | Notes |
|---|---|---|
| Single-char wildcard | `?` | `?` matches exactly 1 character |
| CamelCase matching | `NPE` → `NullPointerException` | IDE-oriented; less relevant for migration rules |
| Full method signature | `Type.method(ParamTypes) ReturnType` | Single pattern with declaring type, params, and return type |
| Constructor patterns | `Type(ParamTypes)` | Constructor name = type name |
| Generic type arguments | `List<String>`, `List<? extends T>` | We strip type parameters (erasure matching only) |
| Module-qualified types | `java.base/java.lang.Object` | Java 9+ module prefix |
| Regular expressions | `R_REGEXP_MATCH` | Eclipse only implements for module search |

## Konveyor-Specific Features (Not in Eclipse SearchPattern)

| Feature | Syntax | Notes |
|---|---|---|
| Annotated queries | `annotated: pattern` | Filter results by annotation on the matched element |
| Annotation elements | `annotated: pattern + elements` | Match annotation with specific element values |
| Dependency queries | `dependency: name/regex + version bounds` | Search resolved Maven/Gradle dependencies |

## Priority Assessment for Migration Analysis

### High Value

- **Method reference expressions** (`Foo::bar`) — common in Java 8+ code, migration rules often need to detect these
- **Constructor declarations** — useful for finding constructors that need parameter changes

### Medium Value

- **INTERFACE as distinct location** — some rules specifically target interface declarations
- **PARAMETER_DECLARATION_TYPE_REFERENCE** — detecting deprecated parameter types
- **THROWS_CLAUSE_TYPE_REFERENCE** — detecting deprecated exception types
- **CAST_TYPE_REFERENCE** — detecting casts to deprecated types

### Low Value (for migration analysis)

- **CamelCase matching** — IDE convenience, not used in migration rules
- **Single-char wildcard `?`** — rarely needed; `*` covers most cases
- **Module-qualified types** — only relevant for Java 9+ module migration
- **Fine-grained qualifier references** (SUPER, THIS, QUALIFIED, IMPLICIT_THIS) — too granular for typical rules
- **Generic type arguments in patterns** — erasure matching sufficient for most migration detection

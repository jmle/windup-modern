package io.konveyor.provider.index;

/**
 * The kind of source code location where a symbol reference appears. Maps to the
 * {@code location} field in Konveyor rule conditions (e.g. {@code IMPORT},
 * {@code ANNOTATION}, {@code METHOD_CALL}). Used to partition symbols in the index
 * and filter query results.
 */
public enum LocationType {
    TYPE,
    INHERITANCE,
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    ANNOTATION,
    IMPLEMENTS_TYPE,
    ENUM,
    RETURN_TYPE,
    IMPORT,
    VARIABLE_DECLARATION,
    TYPE_KEYWORD,
    PACKAGE,
    FIELD,
    METHOD,
    CLASS;

    public static LocationType fromString(String s) {
        if (s == null || s.isEmpty()) {
            return TYPE;
        }
        return switch (s.toLowerCase()) {
            case "inheritance" -> INHERITANCE;
            case "method_call" -> METHOD_CALL;
            case "constructor_call" -> CONSTRUCTOR_CALL;
            case "annotation" -> ANNOTATION;
            case "implements_type" -> IMPLEMENTS_TYPE;
            case "enum" -> ENUM;
            case "return_type" -> RETURN_TYPE;
            case "import" -> IMPORT;
            case "variable_declaration" -> VARIABLE_DECLARATION;
            case "type" -> TYPE_KEYWORD;
            case "package" -> PACKAGE;
            case "field", "field_declaration" -> FIELD;
            case "method" -> METHOD;
            case "class" -> CLASS;
            default -> TYPE;
        };
    }
}

package org.jboss.windup.provider.index;

/**
 * Classification of a symbol, aligned with LSP {@code SymbolKind} values. The
 * {@link #label()} string (e.g. "Module", "Class", "Method") is used as the
 * {@code kind} variable in gRPC incident responses.
 */
public enum SymbolKind {
    FILE("File"),
    MODULE("Module"),
    NAMESPACE("Namespace"),
    PACKAGE("Package"),
    CLASS("Class"),
    METHOD("Method"),
    PROPERTY("Property"),
    FIELD("Field"),
    CONSTRUCTOR("Constructor"),
    ENUM("Enum"),
    INTERFACE("Interface"),
    FUNCTION("Function"),
    VARIABLE("Variable"),
    CONSTANT("Constant");

    private final String label;

    SymbolKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

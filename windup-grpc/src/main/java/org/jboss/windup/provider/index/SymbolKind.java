package org.jboss.windup.provider.index;

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

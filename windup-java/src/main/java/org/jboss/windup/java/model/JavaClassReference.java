package org.jboss.windup.java.model;

import java.util.Objects;

import org.jboss.windup.model.FileModel;

/**
 * Represents a reference to a Java type at a specific location in source code.
 *
 * <p>Modernized from the legacy graph-backed {@code JavaTypeReferenceModel} and
 * {@code FileLocationModel} into a flat POJO. Each instance captures where a
 * fully-qualified type name was referenced, what kind of reference it is (import,
 * method call, annotation, etc.), and the exact source location.</p>
 */
public final class JavaClassReference {

    /**
     * Classifies the kind of reference to a Java type found in source code.
     *
     * <p>Derived from the legacy {@code TypeReferenceLocation} enum with the
     * same set of values relevant to migration analysis.</p>
     */
    public enum ReferenceType {
        /** A Java class imports the type. */
        IMPORT("Import of"),
        /** A Java class declares the type. */
        TYPE("Declares type"),
        /** A Java class declares an enumeration constant. */
        ENUM_CONSTANT("Declares enumeration constant"),
        /** A Java class declares the method. */
        METHOD("Declares method"),
        /** A Java class inherits the type (extends). */
        INHERITANCE("Inherits type"),
        /** A Java class constructs an instance of the type. */
        CONSTRUCTOR_CALL("Constructing type"),
        /** A Java class calls a method on the type. */
        METHOD_CALL("Calls method"),
        /** A method parameter is of the type. */
        METHOD_PARAMETER("Method parameter"),
        /** An annotation of the type is used. */
        ANNOTATION("References annotation"),
        /** A method returns the type. */
        RETURN_TYPE("Returns type"),
        /** The type is used in an instanceof expression. */
        INSTANCE_OF("Instance of type"),
        /** A method declares that it throws the type. */
        THROWS_METHOD_DECLARATION("Throws"),
        /** A throw statement creates an instance of the type. */
        THROW_STATEMENT("Throw"),
        /** A catch clause catches the type. */
        CATCH_EXCEPTION_STATEMENT("Catches exception"),
        /** A field is declared with the type. */
        FIELD_DECLARATION("Declares field"),
        /** A local variable is declared with the type. */
        VARIABLE_DECLARATION("Declares variable"),
        /** A Java class implements the type (interface). */
        IMPLEMENTS_TYPE("Implements type"),
        /** A variable initializer references the type. */
        VARIABLE_INITIALIZER("Variable Initializer"),
        /** A JSP taglib import references the type. */
        TAGLIB_IMPORT("Taglib Import");

        private final String readablePrefix;

        ReferenceType(String readablePrefix) {
            this.readablePrefix = readablePrefix;
        }

        /**
         * Returns a human-readable prefix describing this reference type.
         */
        public String toReadablePrefix() {
            return readablePrefix;
        }
    }

    private String qualifiedName;
    private ReferenceType referenceType;
    private int lineNumber;
    private int columnNumber;
    private FileModel sourceFile;
    private String resolvedSourceSnippet;

    public JavaClassReference() {
    }

    public JavaClassReference(String qualifiedName, ReferenceType referenceType,
                              int lineNumber, int columnNumber) {
        this.qualifiedName = qualifiedName;
        this.referenceType = referenceType;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(ReferenceType referenceType) {
        this.referenceType = referenceType;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public void setColumnNumber(int columnNumber) {
        this.columnNumber = columnNumber;
    }

    public FileModel getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(FileModel sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * Returns the resolved source snippet at this reference location, or
     * {@code null} if resolution was not performed.
     */
    public String getResolvedSourceSnippet() {
        return resolvedSourceSnippet;
    }

    public void setResolvedSourceSnippet(String resolvedSourceSnippet) {
        this.resolvedSourceSnippet = resolvedSourceSnippet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaClassReference that)) return false;
        return lineNumber == that.lineNumber
                && columnNumber == that.columnNumber
                && Objects.equals(qualifiedName, that.qualifiedName)
                && referenceType == that.referenceType
                && Objects.equals(sourceFile, that.sourceFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedName, referenceType, lineNumber, columnNumber);
    }

    @Override
    public String toString() {
        return "JavaClassReference{" +
                "qualifiedName='" + qualifiedName + '\'' +
                ", referenceType=" + referenceType +
                ", lineNumber=" + lineNumber +
                ", columnNumber=" + columnNumber +
                '}';
    }
}

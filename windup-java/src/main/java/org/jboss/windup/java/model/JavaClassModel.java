package org.jboss.windup.java.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jboss.windup.model.FileModel;

/**
 * Represents a Java class (or interface, enum, annotation type) discovered
 * during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code JavaClassModel} into a flat
 * POJO. Inheritance relationships are captured by qualified name rather than
 * object references to avoid circular dependency issues during construction.
 * The source file is referenced via the core {@link FileModel}.</p>
 */
public final class JavaClassModel {

    private String qualifiedName;
    private String packageName;
    private String className;
    private String superClassName;
    private final List<String> interfaces = new ArrayList<>();
    private boolean abstractClass;
    private boolean publicClass;
    private boolean interfaceType;
    private boolean enumType;
    private FileModel sourceFileModel;
    private final List<JavaMethodModel> methods = new ArrayList<>();
    private final List<JavaAnnotationModel> annotations = new ArrayList<>();

    public JavaClassModel() {
    }

    public JavaClassModel(String qualifiedName) {
        this.qualifiedName = qualifiedName;
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot >= 0) {
            this.packageName = qualifiedName.substring(0, lastDot);
            this.className = qualifiedName.substring(lastDot + 1);
        } else {
            this.packageName = "";
            this.className = qualifiedName;
        }
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getSuperClassName() {
        return superClassName;
    }

    public void setSuperClassName(String superClassName) {
        this.superClassName = superClassName;
    }

    /**
     * Returns the list of fully-qualified interface names implemented by this
     * class. The list is mutable.
     */
    public List<String> getInterfaces() {
        return interfaces;
    }

    public boolean isAbstractClass() {
        return abstractClass;
    }

    public void setAbstractClass(boolean abstractClass) {
        this.abstractClass = abstractClass;
    }

    public boolean isPublicClass() {
        return publicClass;
    }

    public void setPublicClass(boolean publicClass) {
        this.publicClass = publicClass;
    }

    public boolean isInterfaceType() {
        return interfaceType;
    }

    public void setInterfaceType(boolean interfaceType) {
        this.interfaceType = interfaceType;
    }

    public boolean isEnumType() {
        return enumType;
    }

    public void setEnumType(boolean enumType) {
        this.enumType = enumType;
    }

    /**
     * Returns the source file that contains this class, or {@code null} if the
     * class was discovered from bytecode only.
     */
    public FileModel getSourceFileModel() {
        return sourceFileModel;
    }

    public void setSourceFileModel(FileModel sourceFileModel) {
        this.sourceFileModel = sourceFileModel;
    }

    /**
     * Returns the methods declared in this class. The list is mutable.
     */
    public List<JavaMethodModel> getMethods() {
        return methods;
    }

    /**
     * Returns the annotations on this class. The list is mutable.
     */
    public List<JavaAnnotationModel> getAnnotations() {
        return annotations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaClassModel that)) return false;
        return Objects.equals(qualifiedName, that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedName);
    }

    @Override
    public String toString() {
        return "JavaClassModel{" +
                "qualifiedName='" + qualifiedName + '\'' +
                ", superClassName='" + superClassName + '\'' +
                ", interfaces=" + interfaces +
                ", abstractClass=" + abstractClass +
                ", interfaceType=" + interfaceType +
                ", enumType=" + enumType +
                '}';
    }
}

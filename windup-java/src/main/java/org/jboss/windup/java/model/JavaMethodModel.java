package org.jboss.windup.java.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a method declared in a Java class.
 *
 * <p>Modernized from the legacy graph-backed {@code JavaMethodModel} and
 * {@code JavaParameterModel} into a flat POJO. Parameter types are stored as
 * a simple list of fully-qualified type names rather than separate vertex
 * frames.</p>
 */
public final class JavaMethodModel {

    private String methodName;
    private String returnType;
    private final List<String> parameterTypes = new ArrayList<>();
    private final List<JavaAnnotationModel> annotations = new ArrayList<>();

    public JavaMethodModel() {
    }

    public JavaMethodModel(String methodName, String returnType) {
        this.methodName = methodName;
        this.returnType = returnType;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    /**
     * Returns the list of fully-qualified parameter type names. The list is
     * mutable; callers may add or remove entries directly.
     */
    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    /**
     * Returns the annotations on this method. The list is mutable.
     */
    public List<JavaAnnotationModel> getAnnotations() {
        return annotations;
    }

    /**
     * Returns the number of parameters this method declares.
     */
    public int getParameterCount() {
        return parameterTypes.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaMethodModel that)) return false;
        return Objects.equals(methodName, that.methodName)
                && Objects.equals(returnType, that.returnType)
                && Objects.equals(parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, returnType, parameterTypes);
    }

    @Override
    public String toString() {
        return "JavaMethodModel{" +
                "methodName='" + methodName + '\'' +
                ", returnType='" + returnType + '\'' +
                ", parameterTypes=" + parameterTypes +
                '}';
    }
}

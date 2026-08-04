package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents a Spring bean definition discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code SpringBeanModel} into a
 * flat POJO that captures the essential metadata needed for migration
 * analysis.</p>
 */
public final class SpringBeanModel {

    private String beanName;
    private String qualifiedClassName;
    private String scope;

    public SpringBeanModel() {
    }

    public SpringBeanModel(String beanName, String qualifiedClassName, String scope) {
        this.beanName = beanName;
        this.qualifiedClassName = qualifiedClassName;
        this.scope = scope;
    }

    public String getBeanName() {
        return beanName;
    }

    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    public String getQualifiedClassName() {
        return qualifiedClassName;
    }

    public void setQualifiedClassName(String qualifiedClassName) {
        this.qualifiedClassName = qualifiedClassName;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpringBeanModel that)) return false;
        return Objects.equals(beanName, that.beanName)
                && Objects.equals(qualifiedClassName, that.qualifiedClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(beanName, qualifiedClassName);
    }

    @Override
    public String toString() {
        return "SpringBeanModel{" +
                "beanName='" + beanName + '\'' +
                ", qualifiedClassName='" + qualifiedClassName + '\'' +
                ", scope='" + scope + '\'' +
                '}';
    }
}

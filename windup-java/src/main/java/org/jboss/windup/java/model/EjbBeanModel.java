package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents an EJB bean discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code EjbBeanBaseModel} and its
 * specializations (session beans, message-driven beans, entity beans) into a
 * single flat POJO that captures the essential metadata needed for migration
 * analysis.</p>
 */
public final class EjbBeanModel {

    /**
     * Enumerates the EJB component types relevant to migration analysis.
     */
    public enum EjbType {
        STATELESS,
        STATEFUL,
        SINGLETON,
        MESSAGE_DRIVEN,
        ENTITY
    }

    private String beanName;
    private EjbType ejbType;
    private String qualifiedClassName;
    private String sessionType;

    public EjbBeanModel() {
    }

    public EjbBeanModel(String beanName, EjbType ejbType, String qualifiedClassName, String sessionType) {
        this.beanName = beanName;
        this.ejbType = ejbType;
        this.qualifiedClassName = qualifiedClassName;
        this.sessionType = sessionType;
    }

    public String getBeanName() {
        return beanName;
    }

    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    public EjbType getEjbType() {
        return ejbType;
    }

    public void setEjbType(EjbType ejbType) {
        this.ejbType = ejbType;
    }

    public String getQualifiedClassName() {
        return qualifiedClassName;
    }

    public void setQualifiedClassName(String qualifiedClassName) {
        this.qualifiedClassName = qualifiedClassName;
    }

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EjbBeanModel that)) return false;
        return Objects.equals(beanName, that.beanName)
                && ejbType == that.ejbType
                && Objects.equals(qualifiedClassName, that.qualifiedClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(beanName, ejbType, qualifiedClassName);
    }

    @Override
    public String toString() {
        return "EjbBeanModel{" +
                "beanName='" + beanName + '\'' +
                ", ejbType=" + ejbType +
                ", qualifiedClassName='" + qualifiedClassName + '\'' +
                ", sessionType='" + sessionType + '\'' +
                '}';
    }
}

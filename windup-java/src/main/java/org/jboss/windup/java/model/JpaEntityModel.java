package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents a JPA entity discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code JPAEntityModel} and its
 * {@code PersistenceEntityModel} supertype into a single flat POJO that
 * captures the essential metadata needed for migration analysis.</p>
 */
public final class JpaEntityModel {

    private String entityName;
    private String tableName;
    private String qualifiedClassName;
    private String persistenceUnitName;

    public JpaEntityModel() {
    }

    public JpaEntityModel(String entityName, String tableName, String qualifiedClassName,
                          String persistenceUnitName) {
        this.entityName = entityName;
        this.tableName = tableName;
        this.qualifiedClassName = qualifiedClassName;
        this.persistenceUnitName = persistenceUnitName;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getQualifiedClassName() {
        return qualifiedClassName;
    }

    public void setQualifiedClassName(String qualifiedClassName) {
        this.qualifiedClassName = qualifiedClassName;
    }

    public String getPersistenceUnitName() {
        return persistenceUnitName;
    }

    public void setPersistenceUnitName(String persistenceUnitName) {
        this.persistenceUnitName = persistenceUnitName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaEntityModel that)) return false;
        return Objects.equals(entityName, that.entityName)
                && Objects.equals(qualifiedClassName, that.qualifiedClassName)
                && Objects.equals(persistenceUnitName, that.persistenceUnitName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityName, qualifiedClassName, persistenceUnitName);
    }

    @Override
    public String toString() {
        return "JpaEntityModel{" +
                "entityName='" + entityName + '\'' +
                ", tableName='" + tableName + '\'' +
                ", qualifiedClassName='" + qualifiedClassName + '\'' +
                ", persistenceUnitName='" + persistenceUnitName + '\'' +
                '}';
    }
}

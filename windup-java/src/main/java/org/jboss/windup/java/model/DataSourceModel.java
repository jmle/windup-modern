package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents a datasource definition discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code DataSourceModel} (which
 * extended {@code JNDIResourceModel}) into a flat POJO that captures the
 * essential metadata needed for migration analysis.</p>
 */
public final class DataSourceModel {

    private String name;
    private String jndiName;
    private String databaseType;
    private String connectionUrl;

    public DataSourceModel() {
    }

    public DataSourceModel(String name, String jndiName, String databaseType, String connectionUrl) {
        this.name = name;
        this.jndiName = jndiName;
        this.databaseType = databaseType;
        this.connectionUrl = connectionUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJndiName() {
        return jndiName;
    }

    public void setJndiName(String jndiName) {
        this.jndiName = jndiName;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
    }

    public String getConnectionUrl() {
        return connectionUrl;
    }

    public void setConnectionUrl(String connectionUrl) {
        this.connectionUrl = connectionUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataSourceModel that)) return false;
        return Objects.equals(name, that.name)
                && Objects.equals(jndiName, that.jndiName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, jndiName);
    }

    @Override
    public String toString() {
        return "DataSourceModel{" +
                "name='" + name + '\'' +
                ", jndiName='" + jndiName + '\'' +
                ", databaseType='" + databaseType + '\'' +
                ", connectionUrl='" + connectionUrl + '\'' +
                '}';
    }
}

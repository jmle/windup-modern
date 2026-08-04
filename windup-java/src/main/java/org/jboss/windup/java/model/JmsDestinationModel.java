package org.jboss.windup.java.model;

import java.util.Objects;

/**
 * Represents a JMS destination (queue or topic) discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code JmsDestinationModel} and
 * its companion {@code JmsDestinationType} enum into a single flat POJO with
 * an inner enum.</p>
 */
public final class JmsDestinationModel {

    /**
     * The type of JMS destination.
     */
    public enum DestinationType {
        QUEUE,
        TOPIC
    }

    private String name;
    private String jndiName;
    private DestinationType destinationType;

    public JmsDestinationModel() {
    }

    public JmsDestinationModel(String name, String jndiName, DestinationType destinationType) {
        this.name = name;
        this.jndiName = jndiName;
        this.destinationType = destinationType;
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

    public DestinationType getDestinationType() {
        return destinationType;
    }

    public void setDestinationType(DestinationType destinationType) {
        this.destinationType = destinationType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JmsDestinationModel that)) return false;
        return Objects.equals(name, that.name)
                && Objects.equals(jndiName, that.jndiName)
                && destinationType == that.destinationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, jndiName, destinationType);
    }

    @Override
    public String toString() {
        return "JmsDestinationModel{" +
                "name='" + name + '\'' +
                ", jndiName='" + jndiName + '\'' +
                ", destinationType=" + destinationType +
                '}';
    }
}

package org.jboss.windup.model;

public record DependencyModel(String groupId, String artifactId, String version, String classifier, String scope) {
    public DependencyModel(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, null, null);
    }
}

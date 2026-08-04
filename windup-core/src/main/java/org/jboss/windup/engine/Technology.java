package org.jboss.windup.engine;

public record Technology(String id, String versionRange) {
    public Technology(String id) {
        this(id, null);
    }
}

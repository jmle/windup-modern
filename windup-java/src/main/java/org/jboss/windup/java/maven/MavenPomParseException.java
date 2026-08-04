package org.jboss.windup.java.maven;

/**
 * Thrown when a Maven POM file cannot be parsed.
 */
public class MavenPomParseException extends RuntimeException {

    public MavenPomParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

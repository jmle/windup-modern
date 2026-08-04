package org.jboss.windup.rules.model;

/**
 * Maps a namespace prefix to its URI. Used for XML namespace declarations.
 */
public record NamespaceMapping(String prefix, String uri) {
}

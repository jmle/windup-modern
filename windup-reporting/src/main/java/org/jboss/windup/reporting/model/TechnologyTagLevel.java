package org.jboss.windup.reporting.model;

/**
 * Indicates the relative importance of a {@link TechnologyTagModel}.
 */
public enum TechnologyTagLevel {

    /** General information about a detected technology. */
    INFORMATIONAL,

    /** A technology detection that is significant for migration planning. */
    IMPORTANT
}

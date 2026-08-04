package org.jboss.windup.reporting.model;

/**
 * Indicates the severity of a migration issue. Ordered from least to most impactful.
 */
public enum Severity {

    /** Informational note, not necessarily requiring any code change. */
    INFORMATION,

    /** Optional improvement; migration will succeed without addressing it. */
    OPTIONAL,

    /** Trivial change, typically a one-line fix. */
    TRIVIAL,

    /** Non-trivial change that requires moderate refactoring. */
    COMPLEX,

    /** Significant redesign of a component or subsystem. */
    REDESIGN,

    /** Fundamental architectural change affecting multiple subsystems. */
    ARCHITECTURAL
}

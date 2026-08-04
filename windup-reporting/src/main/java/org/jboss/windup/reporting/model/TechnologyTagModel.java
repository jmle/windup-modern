package org.jboss.windup.reporting.model;

/**
 * Represents a technology detected in a source file. For example, a file might
 * be tagged with "EJB" or "Hibernate Configuration".
 *
 * @param name  short tag identifying the technology (e.g. "EJB")
 * @param level the relative importance of this tag
 */
public record TechnologyTagModel(String name, TechnologyTagLevel level) {
}

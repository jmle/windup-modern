package org.jboss.windup.reporting.model;

/**
 * An external reference link associated with a classification or hint,
 * pointing to additional documentation or resources.
 *
 * @param title display text for the link
 * @param url   the URL target
 */
public record LinkModel(String title, String url) {
}

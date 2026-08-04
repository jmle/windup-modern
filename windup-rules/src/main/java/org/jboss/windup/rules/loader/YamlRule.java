package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a single rule within a YAML ruleset.
 * Each rule has an id, a condition ({@code when}), and an action ({@code perform}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRule {

    private String id;
    private YamlCondition when;
    private YamlAction perform;

    public YamlRule() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public YamlCondition getWhen() {
        return when;
    }

    public void setWhen(YamlCondition when) {
        this.when = when;
    }

    public YamlAction getPerform() {
        return perform;
    }

    public void setPerform(YamlAction perform) {
        this.perform = perform;
    }
}

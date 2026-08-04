package org.jboss.windup.rules.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the ruleset header and its contained rules within a YAML rule file.
 * Maps to the value under the top-level {@code rules} key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRulesetHeader {

    private String id;
    private String phase;

    @JsonProperty("source-technology")
    private YamlTechnology sourceTechnology;

    @JsonProperty("target-technology")
    private YamlTechnology targetTechnology;

    private List<YamlRule> rules = new ArrayList<>();

    public YamlRulesetHeader() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public YamlTechnology getSourceTechnology() {
        return sourceTechnology;
    }

    public void setSourceTechnology(YamlTechnology sourceTechnology) {
        this.sourceTechnology = sourceTechnology;
    }

    public YamlTechnology getTargetTechnology() {
        return targetTechnology;
    }

    public void setTargetTechnology(YamlTechnology targetTechnology) {
        this.targetTechnology = targetTechnology;
    }

    public List<YamlRule> getRules() {
        return rules;
    }

    public void setRules(List<YamlRule> rules) {
        this.rules = rules;
    }

    /**
     * Simple POJO for source/target technology references.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YamlTechnology {

        private String id;
        private String version;

        public YamlTechnology() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}

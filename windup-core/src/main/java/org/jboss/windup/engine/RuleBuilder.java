package org.jboss.windup.engine;

import java.util.ArrayList;
import java.util.List;

public class RuleBuilder {

    private final List<Rule> rules = new ArrayList<>();
    private String currentId;
    private RuleCondition currentCondition;
    private RuleMetadata currentMetadata;

    private RuleBuilder() {}

    public static RuleBuilder create() {
        return new RuleBuilder();
    }

    public RuleBuilder addRule(String id) {
        this.currentId = id;
        this.currentCondition = null;
        this.currentMetadata = null;
        return this;
    }

    public RuleBuilder when(RuleCondition condition) {
        this.currentCondition = condition;
        return this;
    }

    public RuleBuilder withMetadata(RuleMetadata metadata) {
        this.currentMetadata = metadata;
        return this;
    }

    public RuleBuilder perform(RuleAction action) {
        RuleMetadata meta = currentMetadata != null ? currentMetadata : new RuleMetadata(Phase.MIGRATION_RULES);
        rules.add(new Rule(currentId, currentCondition, action, meta));
        return this;
    }

    public List<Rule> build() {
        return List.copyOf(rules);
    }
}

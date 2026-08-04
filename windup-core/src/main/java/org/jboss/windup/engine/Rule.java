package org.jboss.windup.engine;

public record Rule(String id, RuleCondition condition, RuleAction action, RuleMetadata metadata) {
}

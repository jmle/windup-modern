package org.jboss.windup.engine;

@FunctionalInterface
public interface RuleAction {
    void perform(AnalysisRun run, ConditionResult matched);
}

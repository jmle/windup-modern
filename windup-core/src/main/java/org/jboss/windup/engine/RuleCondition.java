package org.jboss.windup.engine;

import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
public interface RuleCondition {

    ConditionResult evaluate(AnalysisRun run);

    default RuleCondition and(RuleCondition other) {
        return run -> {
            ConditionResult left = this.evaluate(run);
            if (!left.matched()) return ConditionResult.noMatch();
            ConditionResult right = other.evaluate(run);
            if (!right.matched()) return ConditionResult.noMatch();
            @SuppressWarnings("unchecked")
            var combined = new ArrayList<>((List<Object>) left.items());
            combined.addAll(right.items());
            return ConditionResult.match(combined);
        };
    }

    default RuleCondition or(RuleCondition other) {
        return run -> {
            ConditionResult left = this.evaluate(run);
            if (left.matched()) return left;
            return other.evaluate(run);
        };
    }

    default RuleCondition not() {
        return run -> {
            ConditionResult result = this.evaluate(run);
            return result.matched() ? ConditionResult.noMatch() : ConditionResult.match(List.of());
        };
    }
}

package org.jboss.windup.engine;

import java.util.List;

public record ConditionResult(boolean matched, List<?> items) {

    public static ConditionResult match(List<?> items) {
        return new ConditionResult(true, items);
    }

    public static ConditionResult noMatch() {
        return new ConditionResult(false, List.of());
    }
}

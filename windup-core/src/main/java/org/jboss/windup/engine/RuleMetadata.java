package org.jboss.windup.engine;

import java.util.List;
import java.util.Set;

public record RuleMetadata(
        Phase phase,
        Set<String> tags,
        Set<Technology> sourceTechnologies,
        Set<Technology> targetTechnologies,
        List<String> executeAfter,
        List<String> executeBefore
) {
    public RuleMetadata(Phase phase) {
        this(phase, Set.of(), Set.of(), Set.of(), List.of(), List.of());
    }
}

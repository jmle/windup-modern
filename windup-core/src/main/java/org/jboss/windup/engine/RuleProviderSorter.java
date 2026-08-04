package org.jboss.windup.engine;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class RuleProviderSorter {

    public List<RuleProvider> sort(List<RuleProvider> providers) {
        Map<Phase, List<RuleProvider>> byPhase = providers.stream()
                .collect(Collectors.groupingBy(p -> p.getMetadata().phase(), LinkedHashMap::new, Collectors.toList()));

        List<RuleProvider> sorted = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            List<RuleProvider> phaseProviders = byPhase.getOrDefault(phase, List.of());
            sorted.addAll(topologicalSort(phaseProviders));
        }
        return sorted;
    }

    private List<RuleProvider> topologicalSort(List<RuleProvider> providers) {
        if (providers.size() <= 1) return new ArrayList<>(providers);

        Map<String, RuleProvider> byId = new LinkedHashMap<>();
        for (var p : providers) byId.put(p.getMetadata().id(), p);

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (var p : providers) {
            String id = p.getMetadata().id();
            dependencies.computeIfAbsent(id, k -> new LinkedHashSet<>());
            for (String after : p.getMetadata().executeAfter()) {
                if (byId.containsKey(after)) {
                    dependencies.get(id).add(after);
                }
            }
            for (String before : p.getMetadata().executeBefore()) {
                if (byId.containsKey(before)) {
                    dependencies.computeIfAbsent(before, k -> new LinkedHashSet<>()).add(id);
                }
            }
        }

        List<RuleProvider> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();

        for (var p : providers) {
            visit(p.getMetadata().id(), byId, dependencies, visited, visiting, result);
        }
        return result;
    }

    private void visit(String id, Map<String, RuleProvider> byId,
                       Map<String, Set<String>> dependencies,
                       Set<String> visited, Set<String> visiting,
                       List<RuleProvider> result) {
        if (visited.contains(id)) return;
        if (visiting.contains(id)) return;
        visiting.add(id);
        for (String dep : dependencies.getOrDefault(id, Set.of())) {
            visit(dep, byId, dependencies, visited, visiting, result);
        }
        visiting.remove(id);
        visited.add(id);
        result.add(byId.get(id));
    }
}

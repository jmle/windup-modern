package org.jboss.windup.engine;

import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void executesRulesInPhaseOrder() {
        List<String> executionOrder = new ArrayList<>();

        var provider1 = createProvider("late-provider", Phase.MIGRATION_RULES,
                (run, matched) -> executionOrder.add("migration"));
        var provider2 = createProvider("early-provider", Phase.INITIALIZATION,
                (run, matched) -> executionOrder.add("init"));

        var engine = createEngine(List.of(provider1, provider2));
        var run = createRun();

        engine.execute(run);

        assertThat(executionOrder).containsExactly("init", "migration");
    }

    @Test
    void conditionGatesAction() {
        AtomicInteger actionCount = new AtomicInteger();

        var matchingProvider = createProvider("matching", Phase.MIGRATION_RULES,
                r -> ConditionResult.match(List.of("item")),
                (run, matched) -> actionCount.incrementAndGet());

        var nonMatchingProvider = createProvider("non-matching", Phase.MIGRATION_RULES,
                r -> ConditionResult.noMatch(),
                (run, matched) -> actionCount.incrementAndGet());

        var engine = createEngine(List.of(matchingProvider, nonMatchingProvider));
        engine.execute(createRun());

        assertThat(actionCount.get()).isEqualTo(1);
    }

    @Test
    void ruleCanModifyContext() {
        var provider = createProvider("adder", Phase.INITIALIZATION, (run, matched) -> {
            var file = new FileModel(Path.of("/app/Test.java"));
            file.setFileType(FileType.JAVA_SOURCE);
            run.getContext().files().register(file);
        });

        var run = createRun();
        var engine = createEngine(List.of(provider));
        engine.execute(run);

        assertThat(run.getContext().files().size()).isEqualTo(1);
        assertThat(run.getContext().getFilesByType(FileType.JAVA_SOURCE)).hasSize(1);
    }

    @Test
    void cancellationStopsExecution() {
        AtomicInteger count = new AtomicInteger();

        var provider = createProvider("canceller", Phase.INITIALIZATION, (run, matched) -> {
            count.incrementAndGet();
            run.cancel();
        });
        var laterProvider = createProvider("after-cancel", Phase.MIGRATION_RULES,
                (run, matched) -> count.incrementAndGet());

        var run = createRun();
        createEngine(List.of(provider, laterProvider)).execute(run);

        assertThat(count.get()).isEqualTo(1);
        assertThat(run.isCancelled()).isTrue();
    }

    @Test
    void ruleBuilderProducesRules() {
        List<Rule> rules = RuleBuilder.create()
                .addRule("rule-1")
                .when(run -> ConditionResult.match(List.of()))
                .perform((run, matched) -> {})
                .addRule("rule-2")
                .when(run -> ConditionResult.noMatch())
                .perform((run, matched) -> {})
                .build();

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).id()).isEqualTo("rule-1");
        assertThat(rules.get(1).id()).isEqualTo("rule-2");
    }

    @Test
    void conditionComposition() {
        RuleCondition alwaysTrue = run -> ConditionResult.match(List.of("a"));
        RuleCondition alwaysFalse = run -> ConditionResult.noMatch();
        var run = createRun();

        assertThat(alwaysTrue.and(alwaysTrue).evaluate(run).matched()).isTrue();
        assertThat(alwaysTrue.and(alwaysFalse).evaluate(run).matched()).isFalse();
        assertThat(alwaysFalse.or(alwaysTrue).evaluate(run).matched()).isTrue();
        assertThat(alwaysFalse.or(alwaysFalse).evaluate(run).matched()).isFalse();
        assertThat(alwaysTrue.not().evaluate(run).matched()).isFalse();
        assertThat(alwaysFalse.not().evaluate(run).matched()).isTrue();
    }

    @Test
    void dynamicProvidersAreIncludedInExecution() {
        List<String> executionOrder = new ArrayList<>();

        var cdiProvider = createProvider("cdi-provider", Phase.INITIALIZATION,
                (run, matched) -> executionOrder.add("cdi"));
        var dynamicProvider = createProvider("dynamic-provider", Phase.MIGRATION_RULES,
                (run, matched) -> executionOrder.add("dynamic"));

        var engine = createEngine(List.of(cdiProvider));

        // Register the dynamic provider via the registry
        try {
            var registryField = RuleEngine.class.getDeclaredField("dynamicRegistry");
            registryField.setAccessible(true);
            var registry = (DynamicRuleProviderRegistry) registryField.get(engine);
            registry.register(dynamicProvider);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        engine.execute(createRun());

        assertThat(executionOrder).containsExactly("cdi", "dynamic");
    }

    // --- helpers ---

    private AnalysisRun createRun() {
        var config = AnalysisConfiguration.builder()
                .inputPath(tempDir)
                .outputDirectory(tempDir.resolve("output"))
                .build();
        return new AnalysisRun(new AnalysisContext(), config);
    }

    private RuleProvider createProvider(String id, Phase phase, RuleAction action) {
        return createProvider(id, phase, run -> ConditionResult.match(List.of()), action);
    }

    private RuleProvider createProvider(String id, Phase phase, RuleCondition condition, RuleAction action) {
        return new RuleProvider() {
            @Override
            public RuleProviderMetadata getMetadata() {
                return new RuleProviderMetadata(id, phase);
            }
            @Override
            public List<Rule> getRules() {
                return List.of(new Rule(id + "-rule", condition, action, new RuleMetadata(phase)));
            }
        };
    }

    private RuleEngine createEngine(List<RuleProvider> providerList) {
        var engine = new RuleEngine();
        try {
            var providersField = RuleEngine.class.getDeclaredField("providers");
            providersField.setAccessible(true);
            providersField.set(engine, new TestInstance<>(providerList));

            var sorterField = RuleEngine.class.getDeclaredField("sorter");
            sorterField.setAccessible(true);
            sorterField.set(engine, new RuleProviderSorter());

            var registryField = RuleEngine.class.getDeclaredField("dynamicRegistry");
            registryField.setAccessible(true);
            registryField.set(engine, new DynamicRuleProviderRegistry());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return engine;
    }

    @SuppressWarnings("unchecked")
    private static class TestInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final List<T> items;
        TestInstance(List<T> items) { this.items = items; }
        @Override public java.util.stream.Stream<T> stream() { return items.stream(); }
        @Override public java.util.Iterator<T> iterator() { return items.iterator(); }
        @Override public T get() { return items.get(0); }
        @Override public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... qualifiers) { return this; }
        @Override @SuppressWarnings("unchecked") public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override @SuppressWarnings("unchecked") public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return items.isEmpty(); }
        @Override public boolean isAmbiguous() { return items.size() > 1; }
        @Override public boolean isResolvable() { return items.size() == 1; }
        @Override public void destroy(T instance) {}
        @Override public jakarta.enterprise.inject.Instance.Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() { throw new UnsupportedOperationException(); }
    }
}

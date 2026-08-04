package org.jboss.windup.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicRuleProviderRegistryTest {

    private DynamicRuleProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DynamicRuleProviderRegistry();
    }

    @Test
    void startsEmpty() {
        assertThat(registry.getProviders()).isEmpty();
    }

    @Test
    void registerAddsProvider() {
        RuleProvider provider = createProvider("test-provider", Phase.MIGRATION_RULES);

        registry.register(provider);

        assertThat(registry.getProviders()).hasSize(1);
        assertThat(registry.getProviders().get(0).getMetadata().id()).isEqualTo("test-provider");
    }

    @Test
    void registerAllAddsMultipleProviders() {
        RuleProvider provider1 = createProvider("provider-1", Phase.INITIALIZATION);
        RuleProvider provider2 = createProvider("provider-2", Phase.MIGRATION_RULES);

        registry.registerAll(List.of(provider1, provider2));

        assertThat(registry.getProviders()).hasSize(2);
        assertThat(registry.getProviders())
                .extracting(p -> p.getMetadata().id())
                .containsExactly("provider-1", "provider-2");
    }

    @Test
    void clearRemovesAllProviders() {
        registry.register(createProvider("p1", Phase.INITIALIZATION));
        registry.register(createProvider("p2", Phase.MIGRATION_RULES));
        assertThat(registry.getProviders()).hasSize(2);

        registry.clear();

        assertThat(registry.getProviders()).isEmpty();
    }

    @Test
    void getProvidersReturnsUnmodifiableList() {
        registry.register(createProvider("p1", Phase.INITIALIZATION));

        List<RuleProvider> providers = registry.getProviders();

        assertThatThrownBy(() -> providers.add(createProvider("p2", Phase.MIGRATION_RULES)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registerRejectsNull() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provider must not be null");
    }

    @Test
    void registerAllRejectsNullList() {
        assertThatThrownBy(() -> registry.registerAll(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("newProviders must not be null");
    }

    @Test
    void registerAllRejectsNullElement() {
        RuleProvider validProvider = createProvider("valid", Phase.INITIALIZATION);
        List<RuleProvider> listWithNull = new java.util.ArrayList<>();
        listWithNull.add(validProvider);
        listWithNull.add(null);

        assertThatThrownBy(() -> registry.registerAll(listWithNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void multipleRegistrationsAccumulate() {
        registry.register(createProvider("p1", Phase.INITIALIZATION));
        registry.registerAll(List.of(
                createProvider("p2", Phase.MIGRATION_RULES),
                createProvider("p3", Phase.REPORT_GENERATION)
        ));
        registry.register(createProvider("p4", Phase.FINALIZE));

        assertThat(registry.getProviders()).hasSize(4);
        assertThat(registry.getProviders())
                .extracting(p -> p.getMetadata().id())
                .containsExactly("p1", "p2", "p3", "p4");
    }

    @Test
    void clearThenRegisterWorks() {
        registry.register(createProvider("old", Phase.INITIALIZATION));
        registry.clear();
        registry.register(createProvider("new", Phase.MIGRATION_RULES));

        assertThat(registry.getProviders()).hasSize(1);
        assertThat(registry.getProviders().get(0).getMetadata().id()).isEqualTo("new");
    }

    // --- helper ---

    private RuleProvider createProvider(String id, Phase phase) {
        return new RuleProvider() {
            @Override
            public RuleProviderMetadata getMetadata() {
                return new RuleProviderMetadata(id, phase);
            }

            @Override
            public List<Rule> getRules() {
                return List.of();
            }
        };
    }
}

package org.jboss.windup.engine;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Registry for rule providers that are created at runtime rather than
 * discovered via CDI. This allows YAML-loaded rules and other dynamically
 * created providers to be included in engine execution alongside
 * CDI-managed providers.
 * <p>
 * Typical usage: {@link WindupProcessor} loads user rules from configured
 * paths before engine execution and registers them here; the
 * {@link RuleEngine} then combines these with CDI-discovered providers.
 */
@ApplicationScoped
public class DynamicRuleProviderRegistry {

    private static final Logger LOG = Logger.getLogger(DynamicRuleProviderRegistry.class.getName());

    private final List<RuleProvider> providers = new ArrayList<>();

    /**
     * Registers a single rule provider.
     *
     * @param provider the provider to register (must not be null)
     * @throws NullPointerException if provider is null
     */
    public void register(RuleProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        providers.add(provider);
        LOG.fine(() -> "Registered dynamic provider: " + provider.getMetadata().id());
    }

    /**
     * Registers multiple rule providers at once.
     *
     * @param newProviders the providers to register (must not be null, must not contain nulls)
     * @throws NullPointerException if the list or any element is null
     */
    public void registerAll(List<RuleProvider> newProviders) {
        Objects.requireNonNull(newProviders, "newProviders must not be null");
        for (RuleProvider provider : newProviders) {
            register(provider);
        }
    }

    /**
     * Returns an unmodifiable view of all registered dynamic providers.
     *
     * @return immutable list of providers (never null)
     */
    public List<RuleProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /**
     * Removes all registered dynamic providers. Should be called between
     * analysis runs to avoid stale providers accumulating.
     */
    public void clear() {
        int count = providers.size();
        providers.clear();
        LOG.fine(() -> "Cleared " + count + " dynamic providers");
    }
}

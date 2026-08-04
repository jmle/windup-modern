package org.jboss.windup.engine;

import java.util.List;

public interface RuleProvider {
    RuleProviderMetadata getMetadata();
    List<Rule> getRules();
}

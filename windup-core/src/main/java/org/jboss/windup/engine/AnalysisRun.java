package org.jboss.windup.engine;

import org.jboss.windup.model.AnalysisContext;

public class AnalysisRun {

    private final AnalysisContext context;
    private final AnalysisConfiguration configuration;
    private volatile boolean cancelled;

    public AnalysisRun(AnalysisContext context, AnalysisConfiguration configuration) {
        this.context = context;
        this.configuration = configuration;
    }

    public AnalysisContext getContext() { return context; }
    public AnalysisConfiguration getConfiguration() { return configuration; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
}

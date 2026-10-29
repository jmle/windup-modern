package io.konveyor.provider.index;

import io.konveyor.provider.WorkspaceContext;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.IncidentContext;
import io.konveyor.provider.grpc.ProviderEvaluateResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * By-construction oracle for the dependency-vs-application incident flag (MTA-8154): matches found in
 * application source must have {@code IsDependencyIncident=false}, and matches in dependency source
 * {@code true}, so the engine's {@code depLabelSelector} filtering works correctly.
 */
class DependencyIncidentTest {

    @TempDir
    static Path tempDir;
    static WorkspaceContext ctx;

    @BeforeAll
    static void setUp() throws IOException {
        // application source (the analysed project)
        Path app = tempDir.resolve("app/com/app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("AppBean.java"), """
                package com.app;
                import javax.ejb.Stateless;
                @Stateless
                public class AppBean {}
                """);

        // dependency source (indexed separately, OUTSIDE the project location)
        Path dep = tempDir.resolve("dep/com/lib");
        Files.createDirectories(dep);
        Files.writeString(dep.resolve("LibBean.java"), """
                package com.lib;
                import javax.ejb.Stateless;
                @Stateless
                public class LibBean {}
                """);

        Config config = Config.newBuilder().setLocation(tempDir.resolve("app").toString())
                .setAnalysisMode("source-only").build();
        ctx = new WorkspaceContext(0, tempDir.resolve("app").toString(), "source-only", config, 10);
        ctx.index();
        ctx.indexDependencySources(List.of(tempDir.resolve("dep")));
    }

    @Test
    void incidentsAreFlaggedApplicationVsDependency() {
        ProviderEvaluateResponse r = ctx.evaluate("referenced",
                "referenced:\n  pattern: 'javax.ejb.Stateless'\n  location: ANNOTATION\n");
        assertThat(r.getMatched()).isTrue();

        Map<String, Boolean> byFile = r.getIncidentContextsList().stream()
                .collect(java.util.stream.Collectors.toMap(
                        i -> i.getFileURI().substring(i.getFileURI().lastIndexOf('/') + 1),
                        IncidentContext::getIsDependencyIncident, (a, b) -> a));

        assertThat(byFile).containsKeys("AppBean.java", "LibBean.java");
        assertThat(byFile.get("AppBean.java")).as("application source is not a dependency incident").isFalse();
        assertThat(byFile.get("LibBean.java")).as("dependency source is a dependency incident").isTrue();
    }
}

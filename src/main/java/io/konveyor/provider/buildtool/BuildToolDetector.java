package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects which {@link BuildTool} implementation to use for a given project directory.
 * Detection priority: Gradle ({@code build.gradle} / {@code build.gradle.kts}) >
 * Maven ({@code pom.xml}) > Binary ({@code .jar/.war/.ear} extension) > Maven (default).
 */
public class BuildToolDetector {

    private static final Logger LOG = LoggerFactory.getLogger(BuildToolDetector.class);

    public static BuildTool detect(Path projectDir) {
        return detect(projectDir, null);
    }

    public static BuildTool detect(Path projectDir, Path mavenIndexPath) {
        if (Files.exists(projectDir.resolve("build.gradle"))
                || Files.exists(projectDir.resolve("build.gradle.kts"))) {
            LOG.info("Detected Gradle project at {}", projectDir);
            return new GradleBuildTool();
        }

        if (Files.exists(projectDir.resolve("pom.xml"))) {
            LOG.info("Detected Maven project at {}", projectDir);
            return new MavenBuildTool();
        }

        String name = projectDir.getFileName().toString().toLowerCase();
        if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
            LOG.info("Detected binary artifact at {}", projectDir);
            return new BinaryBuildTool(mavenIndexPath);
        }

        LOG.info("Defaulting to Maven build tool for {}", projectDir);
        return new MavenBuildTool();
    }
}

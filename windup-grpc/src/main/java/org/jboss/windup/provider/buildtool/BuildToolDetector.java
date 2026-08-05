package org.jboss.windup.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class BuildToolDetector {

    private static final Logger LOG = LoggerFactory.getLogger(BuildToolDetector.class);

    public static BuildTool detect(Path projectDir) {
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
            return new BinaryBuildTool();
        }

        LOG.info("Defaulting to Maven build tool for {}", projectDir);
        return new MavenBuildTool();
    }
}

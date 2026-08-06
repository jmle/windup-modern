package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle {@link BuildTool} implementation. Runs {@code gradlew dependencies
 * --configuration compileClasspath} (preferring the wrapper when available) and resolves
 * artifact JARs from {@code ~/.gradle/caches/modules-2/files-2.1}.
 */
public class GradleBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(GradleBuildTool.class);

    private static final Pattern DEP_LINE = Pattern.compile(
            "[|+\\-\\\\ ]*([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)");

    private static final int TIMEOUT_MINUTES = 10;

    @Override
    public Type getType() {
        return Type.GRADLE;
    }

    @Override
    public List<ResolvedDependency> getDependencies(Path projectDir) {
        List<String> output = runGradleDependencies(projectDir);
        if (output.isEmpty()) {
            return List.of();
        }

        Path localRepo = getLocalRepoPath();
        List<ResolvedDependency> deps = new ArrayList<>();

        for (String line : output) {
            Matcher m = DEP_LINE.matcher(line);
            if (m.find()) {
                String groupId = m.group(1);
                String artifactId = m.group(2);
                String version = m.group(3);

                Path jarPath = resolveGradleJarPath(localRepo, groupId, artifactId, version);
                boolean hasSource = jarPath != null
                        && Files.exists(jarPath.getParent().getParent().resolve(
                                findSourceHash(jarPath, version)));

                deps.add(new ResolvedDependency(groupId, artifactId, version, null, "compile", jarPath, false));
            }
        }

        LOG.info("Resolved {} Gradle dependencies from {}", deps.size(), projectDir);
        return deps;
    }

    @Override
    public Path getLocalRepoPath() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome != null) {
            return Path.of(gradleHome, "caches", "modules-2", "files-2.1");
        }
        return Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1");
    }

    List<String> runGradleDependencies(Path projectDir) {
        String gradleCmd = findGradleCommand(projectDir);
        List<String> command = List.of(gradleCmd, "dependencies", "--configuration", "compileClasspath", "-q");

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(false);

            Process process = pb.start();
            List<String> output = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                LOG.error("gradle dependencies timed out");
                return List.of();
            }

            if (process.exitValue() != 0) {
                LOG.warn("gradle dependencies exited with code {} for {}", process.exitValue(), projectDir);
            }

            return output;
        } catch (IOException e) {
            LOG.error("Failed to run gradle dependencies in {}", projectDir, e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String findGradleCommand(Path projectDir) {
        Path wrapper = projectDir.resolve("gradlew");
        if (Files.isExecutable(wrapper)) return wrapper.toString();
        return "gradle";
    }

    static Path resolveGradleJarPath(Path cacheDir, String groupId, String artifactId, String version) {
        Path artifactDir = cacheDir.resolve(groupId).resolve(artifactId).resolve(version);
        if (!Files.isDirectory(artifactDir)) return null;

        String jarName = artifactId + "-" + version + ".jar";
        try (var stream = Files.walk(artifactDir, 2)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals(jarName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String findSourceHash(Path jarPath, String version) {
        return "";
    }
}

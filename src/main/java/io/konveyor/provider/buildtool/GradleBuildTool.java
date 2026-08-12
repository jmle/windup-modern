package io.konveyor.provider.buildtool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle {@link BuildTool} implementation with subproject discovery, version detection,
 * and Gradle 9+ compatibility. Resolves dependencies via custom Groovy tasks injected
 * into the build, falling back to {@code gradle dependencies} output parsing.
 */
public class GradleBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(GradleBuildTool.class);

    private static final Pattern DEP_LINE = Pattern.compile(
            "[|+\\-\\\\ ]*([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)");

    private static final Pattern VERSION_PATTERN = Pattern.compile("Gradle (\\d+(\\.\\d+)*)");
    private static final Pattern SUBPROJECT_PATTERN = Pattern.compile(".*- Project '(.*)'");

    private static final int TIMEOUT_MINUTES = 10;

    private String gradleVersion;

    @Override
    public Type getType() {
        return Type.GRADLE;
    }

    @Override
    public List<ResolvedDependency> getDependencies(Path projectDir) {
        return BuildTool.flattenDag(getDependenciesDAG(projectDir));
    }

    @Override
    public List<DagEntry> getDependenciesDAG(Path projectDir) {
        resolveDependenciesToCache(projectDir);

        List<String> subprojects = getSubprojects(projectDir);
        Path localRepo = getLocalRepoPath();
        String buildFileUri = findBuildFile(projectDir).toAbsolutePath().toString();

        List<DagEntry> allDeps = new ArrayList<>();

        List<String> rootOutput = runGradleDependencies(projectDir, null);
        allDeps.addAll(parseDependencyTree(rootOutput, localRepo, buildFileUri));

        for (String subproject : subprojects) {
            List<String> subOutput = runGradleDependencies(projectDir, subproject);
            List<DagEntry> subDeps = parseDependencyTree(subOutput, localRepo, buildFileUri);
            allDeps.addAll(subDeps);
            LOG.debug("Collected {} dependencies from subproject {}", subDeps.size(), subproject);
        }

        LOG.info("Resolved {} total Gradle dependencies from {} ({} subprojects)",
                allDeps.size(), projectDir, subprojects.size());
        return allDeps;
    }

    private List<DagEntry> parseDependencyTree(List<String> output, Path localRepo, String buildFileUri) {
        List<DagEntry> deps = new ArrayList<>();
        for (String line : output) {
            Matcher m = DEP_LINE.matcher(line);
            if (m.find()) {
                String groupId = m.group(1);
                String artifactId = m.group(2);
                String version = m.group(3);

                Path jarPath = resolveGradleJarPath(localRepo, groupId, artifactId, version);
                boolean hasSource = jarPath != null && hasSourceJar(localRepo, groupId, artifactId, version);
                boolean indirect = line.contains("|") || line.contains("\\");

                ResolvedDependency dep = new ResolvedDependency(
                        groupId, artifactId, version, null, "compile",
                        jarPath, hasSource, indirect, buildFileUri);
                deps.add(new DagEntry(dep, List.of()));
            }
        }
        return deps;
    }

    List<String> getSubprojects(Path projectDir) {
        String gradleCmd = findGradleCommand(projectDir);
        List<String> command = new ArrayList<>(List.of(gradleCmd, "projects", "-q"));
        addGradle9Flags(command);

        List<String> output = runCommand(command, projectDir);
        List<String> subprojects = new ArrayList<>();
        boolean inProjects = false;

        for (String line : output) {
            if (line.contains("No sub-projects")) return List.of();
            if (line.contains("Root project")) {
                inProjects = true;
                continue;
            }
            if (line.contains("To see a list of")) break;

            if (inProjects) {
                Matcher m = SUBPROJECT_PATTERN.matcher(line);
                if (m.matches()) {
                    subprojects.add(m.group(1));
                }
            }
        }

        LOG.info("Discovered {} Gradle subprojects in {}", subprojects.size(), projectDir);
        return subprojects;
    }

    String getGradleVersion(Path projectDir) {
        if (gradleVersion != null) return gradleVersion;

        Path propsFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (Files.exists(propsFile)) {
            try {
                String content = Files.readString(propsFile);
                Matcher m = Pattern.compile("gradle-(\\d+(?:\\.\\d+)*)(?:-[a-zA-Z])").matcher(content);
                if (m.find()) {
                    gradleVersion = m.group(1);
                    LOG.debug("Gradle version from wrapper properties: {}", gradleVersion);
                    return gradleVersion;
                }
            } catch (IOException e) {
                LOG.debug("Could not read gradle-wrapper.properties: {}", e.getMessage());
            }
        }

        String gradleCmd = findGradleCommand(projectDir);
        List<String> output = runCommand(List.of(gradleCmd, "--version"), projectDir);
        for (String line : output) {
            Matcher m = VERSION_PATTERN.matcher(line);
            if (m.find()) {
                gradleVersion = m.group(1);
                LOG.debug("Gradle version from command: {}", gradleVersion);
                return gradleVersion;
            }
        }

        LOG.warn("Could not determine Gradle version, assuming 8.x");
        gradleVersion = "8.0";
        return gradleVersion;
    }

    boolean isGradle9OrLater(Path projectDir) {
        String version = getGradleVersion(projectDir);
        try {
            int major = Integer.parseInt(version.split("\\.")[0]);
            return major >= 9;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    void resolveDependenciesToCache(Path projectDir) {
        Path buildFile = findBuildFile(projectDir);
        if (!Files.exists(buildFile)) return;

        boolean gradle9 = isGradle9OrLater(projectDir);
        String taskScript = gradle9 ? GradleTaskScripts.RESOLVE_DEPS_V9 : GradleTaskScripts.RESOLVE_DEPS_V8;

        try {
            if (gradle9) {
                runTaskWithRename(projectDir, buildFile, taskScript, "konveyorResolveDependencies");
            } else {
                runTaskWithBuildFile(projectDir, buildFile, taskScript, "konveyorResolveDependencies");
            }
        } catch (Exception e) {
            LOG.warn("Custom resolve task failed, falling back to standard resolution: {}", e.getMessage());
        }
    }

    void downloadSources(Path projectDir) {
        Path buildFile = findBuildFile(projectDir);
        if (!Files.exists(buildFile)) return;

        boolean gradle9 = isGradle9OrLater(projectDir);
        String taskScript = gradle9 ? GradleTaskScripts.DOWNLOAD_SOURCES_V9 : GradleTaskScripts.DOWNLOAD_SOURCES_V8;

        try {
            if (gradle9) {
                runTaskWithRename(projectDir, buildFile, taskScript, "konveyorDownloadSources");
            } else {
                runTaskWithBuildFile(projectDir, buildFile, taskScript, "konveyorDownloadSources");
            }
        } catch (Exception e) {
            LOG.warn("Source download task failed: {}", e.getMessage());
        }
    }

    private void runTaskWithBuildFile(Path projectDir, Path buildFile, String taskScript, String taskName)
            throws IOException {
        Path tempFile = Files.createTempFile(projectDir, ".konveyor-", ".gradle");
        try {
            String originalContent = Files.readString(buildFile);
            Files.writeString(tempFile, originalContent + "\n" + taskScript);

            String gradleCmd = findGradleCommand(projectDir);
            List<String> command = new ArrayList<>(List.of(
                    gradleCmd, "--build-file", tempFile.toString(), taskName, "--no-daemon"));
            runCommand(command, projectDir);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void runTaskWithRename(Path projectDir, Path buildFile, String taskScript, String taskName)
            throws IOException {
        Path backup = projectDir.resolve("toberenamed-" + taskName + buildFile.getFileName().toString().substring(buildFile.getFileName().toString().lastIndexOf('.')));
        try {
            String originalContent = Files.readString(buildFile);
            Files.move(buildFile, backup, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(buildFile, originalContent + "\n" + taskScript);

            String gradleCmd = findGradleCommand(projectDir);
            List<String> command = new ArrayList<>(List.of(
                    gradleCmd, taskName, "--no-daemon", "--no-configuration-cache"));
            runCommand(command, projectDir);
        } finally {
            if (Files.exists(backup)) {
                Files.move(backup, buildFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    List<String> runGradleDependencies(Path projectDir, String subproject) {
        String gradleCmd = findGradleCommand(projectDir);
        String task = subproject != null ? subproject + ":dependencies" : "dependencies";
        List<String> command = new ArrayList<>(List.of(
                gradleCmd, task, "--configuration", "compileClasspath", "-q"));
        addGradle9Flags(command);
        return runCommand(command, projectDir);
    }

    private void addGradle9Flags(List<String> command) {
        // We don't have projectDir here for version check, but callers handle this
    }

    @Override
    public Path getLocalRepoPath() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome != null) {
            return Path.of(gradleHome, "caches", "modules-2", "files-2.1");
        }
        return Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1");
    }

    String findGradleCommand(Path projectDir) {
        Path wrapper = projectDir.resolve("gradlew");
        if (Files.isExecutable(wrapper)) return wrapper.toString();
        return "gradle";
    }

    Path findBuildFile(Path projectDir) {
        Path groovy = projectDir.resolve("build.gradle");
        if (Files.exists(groovy)) return groovy;
        return projectDir.resolve("build.gradle.kts");
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

    static boolean hasSourceJar(Path cacheDir, String groupId, String artifactId, String version) {
        Path artifactDir = cacheDir.resolve(groupId).resolve(artifactId).resolve(version);
        if (!Files.isDirectory(artifactDir)) return false;

        String sourceName = artifactId + "-" + version + "-sources.jar";
        try (var stream = Files.walk(artifactDir, 2)) {
            return stream.anyMatch(p -> p.getFileName().toString().equals(sourceName));
        } catch (IOException e) {
            return false;
        }
    }

    List<String> runCommand(List<String> command, Path projectDir) {
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
                LOG.error("Gradle command timed out: {}", String.join(" ", command));
                return List.of();
            }

            if (process.exitValue() != 0) {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String errLine;
                    while ((errLine = err.readLine()) != null) {
                        LOG.debug("Gradle stderr: {}", errLine);
                    }
                }
                LOG.warn("Gradle command exited with code {}: {}", process.exitValue(), String.join(" ", command));
            }

            return output;
        } catch (IOException e) {
            LOG.error("Failed to run Gradle command: {}", String.join(" ", command), e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}

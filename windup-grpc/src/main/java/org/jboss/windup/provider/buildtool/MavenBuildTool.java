package org.jboss.windup.provider.buildtool;

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
 * Maven {@link BuildTool} implementation. Runs {@code mvn dependency:tree} to resolve the
 * full transitive dependency graph, then maps each artifact to its JAR in
 * {@code ~/.m2/repository}. Skips test-scoped dependencies.
 */
public class MavenBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(MavenBuildTool.class);

    private static final Pattern DEP_TREE_LINE = Pattern.compile(
            "[|+\\-\\\\ ]*([\\w.\\-]+):([\\w.\\-]+):(\\w+)(?::([\\w.\\-]+))?:([\\w.\\-]+):([\\w.\\-]+)");

    private static final int TIMEOUT_MINUTES = 10;

    @Override
    public Type getType() {
        return Type.MAVEN;
    }

    @Override
    public List<ResolvedDependency> getDependencies(Path projectDir) {
        List<String> depTreeOutput = runMvnDependencyTree(projectDir);
        if (depTreeOutput.isEmpty()) {
            return List.of();
        }

        Path localRepo = getLocalRepoPath();
        List<ResolvedDependency> deps = new ArrayList<>();

        for (String line : depTreeOutput) {
            Matcher m = DEP_TREE_LINE.matcher(line);
            if (m.find()) {
                String groupId = m.group(1);
                String artifactId = m.group(2);
                String packaging = m.group(3);
                String classifier = m.group(4);
                String version = m.group(5);
                String scope = m.group(6);

                if ("test".equals(scope)) continue;

                Path jarPath = resolveJarPath(localRepo, groupId, artifactId, version, classifier, packaging);
                boolean hasSource = jarPath != null && Files.exists(sourceJarPath(jarPath));

                deps.add(new ResolvedDependency(groupId, artifactId, version, classifier, scope, jarPath, hasSource));
            }
        }

        LOG.info("Resolved {} Maven dependencies from {}", deps.size(), projectDir);
        return deps;
    }

    @Override
    public Path getLocalRepoPath() {
        String m2Repo = System.getProperty("maven.repo.local");
        if (m2Repo != null) {
            return Path.of(m2Repo);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    List<String> runMvnDependencyTree(Path projectDir) {
        String mvnCmd = findMvnCommand();
        List<String> command = List.of(mvnCmd, "dependency:tree", "-DoutputType=text", "-q");

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

            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    LOG.debug("[mvn stderr] {}", line);
                }
            }

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                LOG.error("mvn dependency:tree timed out after {} minutes", TIMEOUT_MINUTES);
                return List.of();
            }

            if (process.exitValue() != 0) {
                LOG.warn("mvn dependency:tree exited with code {} for {}", process.exitValue(), projectDir);
            }

            return output;
        } catch (IOException e) {
            LOG.error("Failed to run mvn dependency:tree in {}", projectDir, e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String findMvnCommand() {
        String mvnHome = System.getenv("MAVEN_HOME");
        if (mvnHome != null) {
            Path mvn = Path.of(mvnHome, "bin", "mvn");
            if (Files.isExecutable(mvn)) return mvn.toString();
        }
        Path wrapper = Path.of("mvnw");
        if (Files.isExecutable(wrapper)) return "./mvnw";
        return "mvn";
    }

    static Path resolveJarPath(Path localRepo, String groupId, String artifactId,
                                String version, String classifier, String packaging) {
        Path groupPath = localRepo;
        for (String part : groupId.split("\\.")) {
            groupPath = groupPath.resolve(part);
        }

        String jarName = artifactId + "-" + version;
        if (classifier != null && !classifier.isEmpty()) {
            jarName += "-" + classifier;
        }
        jarName += "." + (packaging != null ? packaging : "jar");

        Path jarPath = groupPath.resolve(artifactId).resolve(version).resolve(jarName);
        return Files.exists(jarPath) ? jarPath : null;
    }

    private static Path sourceJarPath(Path jarPath) {
        String name = jarPath.getFileName().toString();
        String sourceName = name.replace(".jar", "-sources.jar");
        return jarPath.getParent().resolve(sourceName);
    }
}

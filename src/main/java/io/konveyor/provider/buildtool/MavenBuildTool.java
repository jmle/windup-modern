package io.konveyor.provider.buildtool;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven {@link BuildTool} implementation using the embedded Maven Resolver API. Parses
 * {@code pom.xml} and resolves the full transitive dependency graph in-process, without
 * requiring an external {@code mvn} binary. Maps each artifact to its JAR in the local
 * Maven repository.
 */
public class MavenBuildTool implements BuildTool {

    private static final Logger LOG = LoggerFactory.getLogger(MavenBuildTool.class);

    private static final RemoteRepository MAVEN_CENTRAL = new RemoteRepository.Builder(
            "central", "default", "https://repo.maven.apache.org/maven2/").build();

    @Override
    public Type getType() {
        return Type.MAVEN;
    }

    @Override
    public List<ResolvedDependency> getDependencies(Path projectDir) {
        return BuildTool.flattenDag(getDependenciesDAG(projectDir));
    }

    @Override
    public List<DagEntry> getDependenciesDAG(Path projectDir) {
        Path pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            LOG.warn("No pom.xml found in {}", projectDir);
            return List.of();
        }

        try {
            String pomPath = pomFile.toAbsolutePath().toString();
            Path localRepo = getLocalRepoPath();

            List<DagEntry> dag = collectDagFromPom(projectDir, pomPath, localRepo);

            Model model = parsePom(pomFile);
            if (model.getModules() != null && !model.getModules().isEmpty()) {
                for (String module : model.getModules()) {
                    Path moduleDir = projectDir.resolve(module);
                    Path modulePom = moduleDir.resolve("pom.xml");
                    if (!Files.exists(modulePom)) {
                        LOG.debug("Module pom not found: {}", modulePom);
                        continue;
                    }
                    try {
                        List<DagEntry> moduleDag = collectDagFromPom(moduleDir, pomPath, localRepo, model);
                        dag.addAll(moduleDag);
                        LOG.debug("Collected {} dependencies from module {}", moduleDag.size(), module);
                    } catch (Exception e) {
                        LOG.warn("Failed to resolve module {}: {}", module, e.getMessage());
                    }
                }
            }

            LOG.info("Resolved {} top-level Maven dependencies from {}", dag.size(), projectDir);
            return dag;

        } catch (Exception e) {
            LOG.error("Failed to resolve Maven dependencies in {}", projectDir, e);
            return List.of();
        }
    }

    private List<DagEntry> collectDagFromPom(Path projectDir, String pomPath, Path localRepo) throws Exception {
        return collectDagFromPom(projectDir, pomPath, localRepo, null);
    }

    private List<DagEntry> collectDagFromPom(Path projectDir, String pomPath, Path localRepo, Model parentModel) throws Exception {
        DependencyNode root = collectDependencyTree(projectDir, parentModel);
        if (root == null) return new ArrayList<>();

        List<DagEntry> dag = new ArrayList<>();
        for (DependencyNode directChild : root.getChildren()) {
            dag.add(buildDagEntry(directChild, localRepo, pomPath, false));
        }
        return dag;
    }

    DependencyNode collectDependencyTree(Path projectDir) throws Exception {
        return collectDependencyTree(projectDir, null);
    }

    DependencyNode collectDependencyTree(Path projectDir, Model parentModel) throws Exception {
        Path pomFile = projectDir.resolve("pom.xml");
        Model model = parsePom(pomFile);
        if (model.getDependencies().isEmpty()) {
            return null;
        }

        java.util.Properties properties = buildProperties(model, parentModel);

        RepositorySystem repoSystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession(repoSystem);
        List<RemoteRepository> remoteRepos = buildRemoteRepos(model);
        if (parentModel != null) {
            for (RemoteRepository repo : buildRemoteRepos(parentModel)) {
                if (remoteRepos.stream().noneMatch(r -> r.getId().equals(repo.getId()))) {
                    remoteRepos.add(repo);
                }
            }
        }

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(remoteRepos);

        for (org.apache.maven.model.Dependency modelDep : model.getDependencies()) {
            String version = modelDep.getVersion();
            if (version == null) {
                version = findManagedVersion(model, modelDep.getGroupId(), modelDep.getArtifactId());
            }
            if (version == null && parentModel != null) {
                version = findManagedVersion(parentModel, modelDep.getGroupId(), modelDep.getArtifactId());
            }
            version = resolveProperties(version != null ? version : "[0,)", properties);

            String groupId = resolveProperties(modelDep.getGroupId(), properties);
            String artifactId = resolveProperties(modelDep.getArtifactId(), properties);

            String coords = groupId + ":" + artifactId
                    + ":" + (modelDep.getType() != null ? modelDep.getType() : "jar")
                    + (modelDep.getClassifier() != null ? ":" + modelDep.getClassifier() : "")
                    + ":" + version;

            Artifact artifact = new DefaultArtifact(coords);
            Dependency dep = new Dependency(artifact,
                    modelDep.getScope() != null ? modelDep.getScope() : "compile");
            collectRequest.addDependency(dep);
        }

        CollectResult collectResult = repoSystem.collectDependencies(session, collectRequest);
        return collectResult.getRoot();
    }

    private java.util.Properties buildProperties(Model model, Model parentModel) {
        java.util.Properties props = new java.util.Properties();
        if (parentModel != null && parentModel.getProperties() != null) {
            props.putAll(parentModel.getProperties());
            if (parentModel.getVersion() != null) {
                props.putIfAbsent("project.version", parentModel.getVersion());
                props.putIfAbsent("project.parent.version", parentModel.getVersion());
            }
            if (parentModel.getGroupId() != null) {
                props.putIfAbsent("project.parent.groupId", parentModel.getGroupId());
            }
        }
        if (model.getProperties() != null) {
            props.putAll(model.getProperties());
        }
        if (model.getVersion() != null) {
            props.put("project.version", model.getVersion());
        } else if (parentModel != null && parentModel.getVersion() != null) {
            props.putIfAbsent("project.version", parentModel.getVersion());
        }
        if (model.getGroupId() != null) {
            props.put("project.groupId", model.getGroupId());
        } else if (parentModel != null && parentModel.getGroupId() != null) {
            props.putIfAbsent("project.groupId", parentModel.getGroupId());
        }
        return props;
    }

    String resolveProperties(String value, java.util.Properties properties) {
        if (value == null || !value.contains("${")) return value;
        String resolved = value;
        for (int i = 0; i < 5; i++) {
            int start = resolved.indexOf("${");
            if (start < 0) break;
            int end = resolved.indexOf("}", start);
            if (end < 0) break;
            String key = resolved.substring(start + 2, end);
            String replacement = properties.getProperty(key);
            if (replacement == null) break;
            resolved = resolved.substring(0, start) + replacement + resolved.substring(end + 1);
        }
        return resolved;
    }

    private String findManagedVersion(Model parentModel, String groupId, String artifactId) {
        if (parentModel.getDependencyManagement() == null) return null;
        for (org.apache.maven.model.Dependency managed : parentModel.getDependencyManagement().getDependencies()) {
            if (groupId.equals(managed.getGroupId()) && artifactId.equals(managed.getArtifactId())) {
                return managed.getVersion();
            }
        }
        return null;
    }

    private DagEntry buildDagEntry(DependencyNode node, Path localRepo, String pomPath,
                                   boolean indirect) {
        return buildDagEntry(node, localRepo, pomPath, indirect, null);
    }

    private DagEntry buildDagEntry(DependencyNode node, Path localRepo, String pomPath,
                                   boolean indirect, ResolvedDependency rootDirect) {
        ResolvedDependency dep = toResolvedDependency(node, localRepo, pomPath, indirect, rootDirect);
        ResolvedDependency parentForChildren = rootDirect != null ? rootDirect : dep;
        List<DagEntry> children = new ArrayList<>();
        for (DependencyNode child : node.getChildren()) {
            children.add(buildDagEntry(child, localRepo, pomPath, true, parentForChildren));
        }
        return new DagEntry(dep, children);
    }

    private ResolvedDependency toResolvedDependency(DependencyNode node, Path localRepo,
                                                    String pomPath, boolean indirect,
                                                    ResolvedDependency baseDep) {
        Artifact artifact = node.getArtifact();
        if (artifact == null) {
            return new ResolvedDependency("", "", "", null, "compile", null, false, indirect, pomPath);
        }

        String scope = node.getDependency() != null ? node.getDependency().getScope() : "compile";

        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        String version = artifact.getVersion();
        String classifier = artifact.getClassifier().isEmpty() ? null : artifact.getClassifier();
        String extension = artifact.getExtension();

        Path jarPath = resolveJarPath(localRepo, groupId, artifactId, version, classifier, extension);
        boolean hasSource = jarPath != null && Files.exists(sourceJarPath(jarPath));

        return new ResolvedDependency(
                groupId, artifactId, version, classifier, scope, jarPath, hasSource,
                indirect, pomPath, baseDep);
    }

    private Model parsePom(Path pomFile) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (var fr = new FileReader(pomFile.toFile())) {
            return reader.read(fr);
        }
    }

    @SuppressWarnings("deprecation")
    private RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    @SuppressWarnings("deprecation")
    private DefaultRepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(getLocalRepoPath().toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    private List<RemoteRepository> buildRemoteRepos(Model model) {
        List<RemoteRepository> repos = new ArrayList<>();
        if (model.getRepositories() != null) {
            for (org.apache.maven.model.Repository repo : model.getRepositories()) {
                repos.add(new RemoteRepository.Builder(
                        repo.getId(), "default", repo.getUrl()).build());
            }
        }
        repos.add(MAVEN_CENTRAL);
        return repos;
    }

    @Override
    public Path getLocalRepoPath() {
        String m2Repo = System.getProperty("maven.repo.local");
        if (m2Repo != null) {
            return Path.of(m2Repo);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
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

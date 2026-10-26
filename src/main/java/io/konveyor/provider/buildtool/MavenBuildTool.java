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
import org.eclipse.aether.resolution.ArtifactRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            LOG.warn("Aether dependency resolution failed, falling back to pom.xml parsing: {}", e.getMessage());
            return fallbackFromPom(pomFile);
        }
    }

    private List<DagEntry> fallbackFromPom(Path pomFile) {
        try {
            Model model = parsePom(pomFile);
            java.util.Properties properties = buildProperties(model, null);
            String pomPath = pomFile.toAbsolutePath().toString();
            Path localRepo = getLocalRepoPath();
            List<DagEntry> dag = new ArrayList<>();

            for (org.apache.maven.model.Dependency modelDep : model.getDependencies()) {
                String version = modelDep.getVersion();
                if (version == null) {
                    version = findManagedVersion(model, modelDep.getGroupId(), modelDep.getArtifactId());
                }
                version = resolveProperties(version, properties);

                String groupId = resolveProperties(modelDep.getGroupId(), properties);
                String artifactId = resolveProperties(modelDep.getArtifactId(), properties);
                String scope = modelDep.getScope() != null ? modelDep.getScope() : "compile";
                String classifier = modelDep.getClassifier();

                Path jarPath = version != null
                        ? resolveJarPath(localRepo, groupId, artifactId, version, classifier,
                                modelDep.getType() != null ? modelDep.getType() : "jar")
                        : null;

                ResolvedDependency dep = new ResolvedDependency(
                        groupId, artifactId, version, classifier, scope, jarPath,
                        jarPath != null && java.nio.file.Files.exists(sourceJarPath(jarPath)),
                        false, pomPath);
                dag.add(new DagEntry(dep, List.of()));
            }

            LOG.info("Fallback: parsed {} direct dependencies from {}", dag.size(), pomFile);
            return dag;
        } catch (Exception e) {
            LOG.error("Fallback pom parsing also failed for {}", pomFile, e);
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

        downloadBomPoms(model, parentModel, repoSystem, session, remoteRepos);

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
            if (version == null) {
                LOG.warn("No version found for {}:{} (likely from an unresolved BOM import), skipping from transitive resolution",
                        modelDep.getGroupId(), modelDep.getArtifactId());
                continue;
            }
            version = resolveProperties(version, properties);

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

    private static final int MAX_BOM_DEPTH = 10;

    private static boolean isImportBom(org.apache.maven.model.Dependency d) {
        return "pom".equals(d.getType()) && "import".equals(d.getScope());
    }

    String findManagedVersion(Model model, String groupId, String artifactId) {
        if (model.getDependencyManagement() == null) return null;
        java.util.Properties properties = buildProperties(model, null);

        // 1. Direct dependencyManagement entry.
        for (org.apache.maven.model.Dependency managed : model.getDependencyManagement().getDependencies()) {
            if (isImportBom(managed)) continue;
            String mg = resolveProperties(managed.getGroupId(), properties);
            String ma = resolveProperties(managed.getArtifactId(), properties);
            if (groupId.equals(mg) && artifactId.equals(ma)) {
                return resolveProperties(managed.getVersion(), properties);
            }
        }

        // 2. Imported BOMs — resolved recursively (nested imports + parent chain).
        for (org.apache.maven.model.Dependency managed : model.getDependencyManagement().getDependencies()) {
            if (!isImportBom(managed)) continue;
            String bg = resolveProperties(managed.getGroupId(), properties);
            String ba = resolveProperties(managed.getArtifactId(), properties);
            String bv = resolveProperties(managed.getVersion(), properties);
            String found = findVersionInBom(bg, ba, bv, groupId, artifactId, new HashSet<>(), 0);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolves the managed version of {@code targetG:targetA} from the BOM {@code bomG:bomA:bomV},
     * recursing into the BOM's own imported BOMs and walking its parent chain. All poms are read from
     * the local repository (populated beforehand by {@link #downloadBomPoms}). The {@code visited} set
     * guards against import cycles.
     */
    private String findVersionInBom(String bomG, String bomA, String bomV,
                                    String targetG, String targetA,
                                    Set<String> visited, int depth) {
        if (depth > MAX_BOM_DEPTH || bomG == null || bomA == null || bomV == null) return null;
        if (!visited.add(bomG + ":" + bomA + ":" + bomV)) return null;

        Model bomModel = loadPomFromRepo(bomG, bomA, bomV);
        if (bomModel == null) return null;

        List<Model> chain = modelWithParents(bomModel);
        java.util.Properties props = effectiveProperties(bomModel);

        // 1. Direct management across the BOM and its parents.
        for (Model m : chain) {
            if (m.getDependencyManagement() == null) continue;
            for (org.apache.maven.model.Dependency managed : m.getDependencyManagement().getDependencies()) {
                if (isImportBom(managed)) continue;
                String mg = resolveProperties(managed.getGroupId(), props);
                String ma = resolveProperties(managed.getArtifactId(), props);
                if (targetG.equals(mg) && targetA.equals(ma)) {
                    String ver = resolveProperties(managed.getVersion(), props);
                    if (ver != null) return ver;
                }
            }
        }

        // 2. Nested imported BOMs across the BOM and its parents.
        for (Model m : chain) {
            if (m.getDependencyManagement() == null) continue;
            for (org.apache.maven.model.Dependency managed : m.getDependencyManagement().getDependencies()) {
                if (!isImportBom(managed)) continue;
                String ng = resolveProperties(managed.getGroupId(), props);
                String na = resolveProperties(managed.getArtifactId(), props);
                String nv = resolveProperties(managed.getVersion(), props);
                String found = findVersionInBom(ng, na, nv, targetG, targetA, visited, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Loads and parses a pom from the local repository, or returns null if absent/unparseable. */
    private Model loadPomFromRepo(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) return null;
        Path pom = resolveJarPath(getLocalRepoPath(), groupId, artifactId, version, null, "pom");
        if (pom == null) return null;
        try {
            return parsePom(pom);
        } catch (Exception e) {
            LOG.debug("Failed to parse pom {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
            return null;
        }
    }

    /** The model plus its parent chain (parents loaded from the local repo), closest first. */
    private List<Model> modelWithParents(Model model) {
        List<Model> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Model cur = model;
        while (cur != null && chain.size() <= MAX_BOM_DEPTH) {
            chain.add(cur);
            org.apache.maven.model.Parent p = cur.getParent();
            if (p == null) break;
            if (!seen.add(p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion())) break;
            cur = loadPomFromRepo(p.getGroupId(), p.getArtifactId(), p.getVersion());
        }
        return chain;
    }

    /** Properties merged across the model's parent chain (child overrides parent), plus project coords. */
    private java.util.Properties effectiveProperties(Model model) {
        List<Model> chain = modelWithParents(model);
        java.util.Properties props = new java.util.Properties();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).getProperties() != null) props.putAll(chain.get(i).getProperties());
        }
        Model leaf = chain.get(0);
        String v = leaf.getVersion() != null ? leaf.getVersion()
                : (leaf.getParent() != null ? leaf.getParent().getVersion() : null);
        if (v != null) props.putIfAbsent("project.version", v);
        String g = leaf.getGroupId() != null ? leaf.getGroupId()
                : (leaf.getParent() != null ? leaf.getParent().getGroupId() : null);
        if (g != null) props.putIfAbsent("project.groupId", g);
        return props;
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

    private void downloadBomPoms(Model model, Model parentModel,
                                  RepositorySystem repoSystem,
                                  DefaultRepositorySystemSession session,
                                  List<RemoteRepository> remoteRepos) {
        Set<String> visited = new HashSet<>();
        List<Model> models = parentModel != null ? List.of(model, parentModel) : List.of(model);
        for (Model m : models) {
            if (m.getDependencyManagement() == null) continue;
            java.util.Properties properties = buildProperties(m, parentModel);
            for (org.apache.maven.model.Dependency managed : m.getDependencyManagement().getDependencies()) {
                if (!isImportBom(managed)) continue;
                downloadBomClosure(
                        resolveProperties(managed.getGroupId(), properties),
                        resolveProperties(managed.getArtifactId(), properties),
                        resolveProperties(managed.getVersion(), properties),
                        repoSystem, session, remoteRepos, visited, 0);
            }
        }
    }

    /**
     * Downloads a BOM pom and its transitive closure (nested imported BOMs + parent chain) into the
     * local repository, so that {@link #findVersionInBom} can read the full graph offline.
     */
    private void downloadBomClosure(String groupId, String artifactId, String version,
                                     RepositorySystem repoSystem,
                                     DefaultRepositorySystemSession session,
                                     List<RemoteRepository> remoteRepos,
                                     Set<String> visited, int depth) {
        if (depth > MAX_BOM_DEPTH || groupId == null || artifactId == null || version == null) return;
        if (!visited.add(groupId + ":" + artifactId + ":" + version)) return;

        if (resolveJarPath(getLocalRepoPath(), groupId, artifactId, version, null, "pom") == null) {
            try {
                Artifact bomArtifact = new DefaultArtifact(groupId, artifactId, "pom", version);
                repoSystem.resolveArtifact(session, new ArtifactRequest(bomArtifact, remoteRepos, null));
                LOG.info("Downloaded BOM pom {}:{}:{}", groupId, artifactId, version);
            } catch (Exception e) {
                LOG.warn("Failed to download BOM pom {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
                return;
            }
        }

        Model m = loadPomFromRepo(groupId, artifactId, version);
        if (m == null) return;

        // Parent chain first, so property resolution for nested imports can see inherited properties.
        if (m.getParent() != null) {
            downloadBomClosure(m.getParent().getGroupId(), m.getParent().getArtifactId(),
                    m.getParent().getVersion(), repoSystem, session, remoteRepos, visited, depth + 1);
        }

        if (m.getDependencyManagement() != null) {
            java.util.Properties props = effectiveProperties(m);
            for (org.apache.maven.model.Dependency managed : m.getDependencyManagement().getDependencies()) {
                if (!isImportBom(managed)) continue;
                downloadBomClosure(
                        resolveProperties(managed.getGroupId(), props),
                        resolveProperties(managed.getArtifactId(), props),
                        resolveProperties(managed.getVersion(), props),
                        repoSystem, session, remoteRepos, visited, depth + 1);
            }
        }
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
        session.setConfigProperty("aether.connector.connectTimeout", 10_000);
        session.setConfigProperty("aether.connector.requestTimeout", 30_000);
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

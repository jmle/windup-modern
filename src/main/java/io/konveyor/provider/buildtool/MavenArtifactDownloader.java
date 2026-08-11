package io.konveyor.provider.buildtool;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class MavenArtifactDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(MavenArtifactDownloader.class);
    private static final String MVN_URI_PREFIX = "mvn://";

    public static boolean isMvnUri(String location) {
        return location != null && location.startsWith(MVN_URI_PREFIX);
    }

    public record MvnCoordinates(String groupId, String artifactId, String version,
                                  String classifier, Path outputDir) {}

    public static MvnCoordinates parseUri(String uri) {
        String stripped = uri.substring(MVN_URI_PREFIX.length());

        Path outputDir = null;
        int atIdx = stripped.indexOf('@');
        if (atIdx >= 0) {
            outputDir = Path.of(stripped.substring(atIdx + 1));
            stripped = stripped.substring(0, atIdx);
        }

        String[] parts = stripped.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("mvn:// URI requires at least groupId:artifactId:version, got: " + uri);
        }

        String classifier = parts.length >= 4 ? parts[3] : null;
        return new MvnCoordinates(parts[0], parts[1], parts[2], classifier, outputDir);
    }

    public Path download(String mvnUri, Path workDir) throws IOException {
        MvnCoordinates coords = parseUri(mvnUri);
        LOG.info("Downloading Maven artifact: {}:{}:{}", coords.groupId(), coords.artifactId(), coords.version());

        Path targetDir = coords.outputDir() != null ? coords.outputDir() : workDir;
        Files.createDirectories(targetDir);

        try {
            Path resolvedFile = resolveArtifact(coords);
            String fileName = coords.artifactId() + "-" + coords.version()
                    + (coords.classifier() != null && !"jar".equals(coords.classifier()) ? "-" + coords.classifier() : "")
                    + ".jar";
            Path destination = targetDir.resolve(fileName);
            Files.copy(resolvedFile, destination, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Downloaded artifact to {}", destination);
            return destination;
        } catch (ArtifactResolutionException e) {
            throw new IOException("Failed to download Maven artifact: " + mvnUri, e);
        }
    }

    @SuppressWarnings("deprecation")
    private Path resolveArtifact(MvnCoordinates coords) throws ArtifactResolutionException {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        RepositorySystem system = locator.getService(RepositorySystem.class);

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepoPath = Path.of(System.getProperty("user.home"), ".m2", "repository");
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        String extension = "jar";
        String coordsStr = coords.groupId() + ":" + coords.artifactId() + ":" + extension
                + (coords.classifier() != null ? ":" + coords.classifier() : "")
                + ":" + coords.version();

        Artifact artifact = new DefaultArtifact(coordsStr);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(List.of(
                new RemoteRepository.Builder("central", "default",
                        "https://repo.maven.apache.org/maven2/").build()));

        ArtifactResult result = system.resolveArtifact(session, request);
        return result.getArtifact().getFile().toPath();
    }
}

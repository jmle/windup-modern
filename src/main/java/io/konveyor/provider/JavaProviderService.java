package io.konveyor.provider;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.stub.StreamObserver;
import io.konveyor.provider.buildtool.MavenArtifactDownloader;
import io.konveyor.provider.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC service implementing the Konveyor {@code ProviderService} contract. Handles
 * workspace lifecycle (Init/Stop), symbol evaluation, and dependency retrieval.
 * Each Init call creates an isolated {@link WorkspaceContext} identified by a numeric ID.
 */
public class JavaProviderService extends ProviderServiceGrpc.ProviderServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(JavaProviderService.class);

    private final int contextLines;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, WorkspaceContext> workspaces = new ConcurrentHashMap<>();

    public JavaProviderService(int contextLines) {
        this.contextLines = contextLines;
    }

    @Override
    public void capabilities(Empty request, StreamObserver<CapabilitiesResponse> responseObserver) {
        Capability referenced = Capability.newBuilder()
                .setName("referenced")
                .build();
        Capability dependency = Capability.newBuilder()
                .setName("dependency")
                .build();

        CapabilitiesResponse response = CapabilitiesResponse.newBuilder()
                .addCapabilities(referenced)
                .addCapabilities(dependency)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void init(Config request, StreamObserver<InitResponse> responseObserver) {
        long id = nextId.getAndIncrement();
        String location = request.getLocation();
        String analysisMode = request.getAnalysisMode();

        LOG.info("Init workspace id={} location={} mode={}", id, location, analysisMode);

        try {
            if (MavenArtifactDownloader.isMvnUri(location)) {
                MavenArtifactDownloader downloader = new MavenArtifactDownloader();
                Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "java-provider-mvn-" + id);
                Path downloaded = downloader.download(location, workDir);
                location = downloaded.toString();
                LOG.info("Downloaded mvn artifact to {}", location);
            }

            configureProxy(request);
            configureMavenSettings(request);

            WorkspaceContext ctx = new WorkspaceContext(id, location, analysisMode, request, contextLines);
            ctx.index();
            workspaces.put(id, ctx);

            InitResponse response = InitResponse.newBuilder()
                    .setSuccessful(true)
                    .setId(id)
                    .build();
            responseObserver.onNext(response);
        } catch (Exception e) {
            LOG.error("Init failed for location={}", location, e);
            InitResponse response = InitResponse.newBuilder()
                    .setSuccessful(false)
                    .setError(e.getMessage())
                    .setId(id)
                    .build();
            responseObserver.onNext(response);
        }
        responseObserver.onCompleted();
    }

    @Override
    public void evaluate(EvaluateRequest request, StreamObserver<EvaluateResponse> responseObserver) {
        long id = request.getId();
        String cap = request.getCap();
        String conditionInfo = request.getConditionInfo();

        WorkspaceContext ctx = workspaces.get(id);
        if (ctx == null) {
            responseObserver.onNext(EvaluateResponse.newBuilder()
                    .setSuccessful(false)
                    .setError("unknown workspace id: " + id)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        try {
            ProviderEvaluateResponse result = ctx.evaluate(cap, conditionInfo);
            responseObserver.onNext(EvaluateResponse.newBuilder()
                    .setSuccessful(true)
                    .setResponse(result)
                    .build());
        } catch (Exception e) {
            LOG.error("Evaluate failed cap={} id={}", cap, id, e);
            responseObserver.onNext(EvaluateResponse.newBuilder()
                    .setSuccessful(false)
                    .setError(e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void stop(ServiceRequest request, StreamObserver<Empty> responseObserver) {
        long id = request.getId();
        WorkspaceContext ctx = workspaces.remove(id);
        if (ctx != null) {
            LOG.info("Stopped workspace id={}", id);
        }
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void getDependencies(ServiceRequest request, StreamObserver<DependencyResponse> responseObserver) {
        long id = request.getId();
        WorkspaceContext ctx = workspaces.get(id);
        if (ctx == null) {
            responseObserver.onNext(DependencyResponse.newBuilder()
                    .setSuccessful(false)
                    .setError("unknown workspace id: " + id)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        try {
            DependencyResponse result = ctx.getDependencies();
            responseObserver.onNext(result);
        } catch (Exception e) {
            LOG.error("GetDependencies failed id={}", id, e);
            responseObserver.onNext(DependencyResponse.newBuilder()
                    .setSuccessful(false)
                    .setError(e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getDependenciesDAG(ServiceRequest request, StreamObserver<DependencyDAGResponse> responseObserver) {
        long id = request.getId();
        WorkspaceContext ctx = workspaces.get(id);
        if (ctx == null) {
            responseObserver.onNext(DependencyDAGResponse.newBuilder()
                    .setSuccessful(false)
                    .setError("unknown workspace id: " + id)
                    .build());
            responseObserver.onCompleted();
            return;
        }

        try {
            DependencyDAGResponse result = ctx.getDependenciesDAG();
            responseObserver.onNext(result);
        } catch (Exception e) {
            LOG.error("GetDependenciesDAG failed id={}", id, e);
            responseObserver.onNext(DependencyDAGResponse.newBuilder()
                    .setSuccessful(false)
                    .setError(e.getMessage())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void notifyFileChanges(NotifyFileChangesRequest request, StreamObserver<NotifyFileChangesResponse> responseObserver) {
        responseObserver.onNext(NotifyFileChangesResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void prepare(PrepareRequest request, StreamObserver<PrepareResponse> responseObserver) {
        responseObserver.onNext(PrepareResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void streamPrepareProgress(PrepareProgressRequest request, StreamObserver<ProgressEvent> responseObserver) {
        responseObserver.onCompleted();
    }

    private void configureProxy(Config config) {
        if (!config.hasProxy()) return;
        Proxy proxy = config.getProxy();

        if (!proxy.getHTTPProxy().isEmpty()) {
            try {
                URI uri = new URI(proxy.getHTTPProxy());
                if (uri.getHost() != null) {
                    System.setProperty("http.proxyHost", uri.getHost());
                    System.setProperty("http.proxyPort",
                            String.valueOf(uri.getPort() > 0 ? uri.getPort() : 80));
                }
            } catch (URISyntaxException e) {
                LOG.warn("Invalid HTTP proxy URL: {}", proxy.getHTTPProxy());
            }
        }

        if (!proxy.getHTTPSProxy().isEmpty()) {
            try {
                URI uri = new URI(proxy.getHTTPSProxy());
                if (uri.getHost() != null) {
                    System.setProperty("https.proxyHost", uri.getHost());
                    System.setProperty("https.proxyPort",
                            String.valueOf(uri.getPort() > 0 ? uri.getPort() : 443));
                }
            } catch (URISyntaxException e) {
                LOG.warn("Invalid HTTPS proxy URL: {}", proxy.getHTTPSProxy());
            }
        }

        if (!proxy.getNoProxy().isEmpty()) {
            String nonProxyHosts = proxy.getNoProxy()
                    .replace(",", "|")
                    .replaceAll("\\s+", "");
            System.setProperty("http.nonProxyHosts", nonProxyHosts);
        }

        LOG.info("Configured proxy settings");
    }

    private void configureMavenSettings(Config config) {
        String mavenCacheDir = WorkspaceContext.extractStringConfig(config, "mavenCacheDir");

        if (mavenCacheDir != null) {
            System.setProperty("maven.repo.local", mavenCacheDir);
            LOG.info("Using custom Maven local repository: {}", mavenCacheDir);
        }

        String httpProxy = null;
        String httpsProxy = null;
        String noProxy = null;
        if (config.hasProxy()) {
            Proxy proxy = config.getProxy();
            httpProxy = proxy.getHTTPProxy().isEmpty() ? null : proxy.getHTTPProxy();
            httpsProxy = proxy.getHTTPSProxy().isEmpty() ? null : proxy.getHTTPSProxy();
            noProxy = proxy.getNoProxy().isEmpty() ? null : proxy.getNoProxy();
        }

        if (mavenCacheDir != null || httpProxy != null || httpsProxy != null) {
            try {
                MavenSettingsGenerator generator = new MavenSettingsGenerator();
                Path settingsFile = generator.generate(mavenCacheDir, httpProxy, httpsProxy, noProxy);
                LOG.info("Maven global settings written to {}", settingsFile);
            } catch (IOException e) {
                LOG.warn("Failed to generate Maven settings: {}", e.getMessage());
            }
        }
    }
}

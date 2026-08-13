package io.konveyor.provider;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point for the Konveyor Java analysis provider. Starts a gRPC server on the
 * specified port, registering both {@link JavaProviderService} and {@link CodeSnipService}.
 */
public class JavaProviderMain {

    private static final Logger LOG = LoggerFactory.getLogger(JavaProviderMain.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 0;
        int contextLines = 10;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--port=")) {
                port = Integer.parseInt(args[i].substring("--port=".length()));
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].startsWith("--contxtLines=")) {
                contextLines = Integer.parseInt(args[i].substring("--contxtLines=".length()));
            } else if ("--contxtLines".equals(args[i]) && i + 1 < args.length) {
                contextLines = Integer.parseInt(args[++i]);
            }
        }

        if (port == 0) {
            LOG.error("--port must be set");
            System.exit(1);
        }

        JavaProviderService providerService = new JavaProviderService(contextLines);
        CodeSnipService codeSnipService = new CodeSnipService(contextLines);
        providerService.setCodeSnipService(codeSnipService);
        DependencyLocationService depLocationService = new DependencyLocationService();

        Server server = ServerBuilder.forPort(port)
                .addService(providerService)
                .addService(codeSnipService)
                .addService(depLocationService)
                .addService(ProtoReflectionService.newInstance())
                .build();

        server.start();
        LOG.info("Java provider started on port {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down Java provider");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}

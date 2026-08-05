package org.jboss.windup.provider;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class JavaProviderMain {

    private static final Logger LOG = LoggerFactory.getLogger(JavaProviderMain.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 0;
        int contextLines = 10;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "--contxtLines" -> {
                    if (i + 1 < args.length) contextLines = Integer.parseInt(args[++i]);
                }
            }
        }

        if (port == 0) {
            LOG.error("--port must be set");
            System.exit(1);
        }

        JavaProviderService providerService = new JavaProviderService(contextLines);
        CodeSnipService codeSnipService = new CodeSnipService(contextLines);

        Server server = ServerBuilder.forPort(port)
                .addService(providerService)
                .addService(codeSnipService)
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

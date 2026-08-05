package org.jboss.windup.provider;

import io.grpc.stub.StreamObserver;
import org.jboss.windup.provider.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CodeSnipService extends ProviderCodeLocationServiceGrpc.ProviderCodeLocationServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(CodeSnipService.class);

    private final int contextLines;

    public CodeSnipService(int contextLines) {
        this.contextLines = contextLines;
    }

    @Override
    public void getCodeSnip(GetCodeSnipRequest request, StreamObserver<GetCodeSnipResponse> responseObserver) {
        String fileUri = request.getUri();
        Location loc = request.getCodeLocation();

        try {
            String snippet = extractSnippet(fileUri, (int) loc.getStartPosition().getLine(), contextLines);
            responseObserver.onNext(GetCodeSnipResponse.newBuilder()
                    .setSnip(snippet)
                    .build());
        } catch (Exception e) {
            LOG.error("Failed to get code snippet for {}", fileUri, e);
            responseObserver.onNext(GetCodeSnipResponse.newBuilder()
                    .setSnip("")
                    .build());
        }
        responseObserver.onCompleted();
    }

    static String extractSnippet(String fileUri, int targetLine, int contextLines) throws IOException {
        Path path = Path.of(URI.create(fileUri));
        List<String> lines = Files.readAllLines(path);

        int startLine = Math.max(0, targetLine - contextLines);
        int endLine = Math.min(lines.size(), targetLine + contextLines + 1);

        int maxLineNumWidth = String.valueOf(endLine).length();

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < endLine; i++) {
            sb.append("\n");
            String lineNum = String.valueOf(i + 1);
            sb.append(" ".repeat(maxLineNumWidth - lineNum.length()));
            sb.append(lineNum);
            sb.append("  ");
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}

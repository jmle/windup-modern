package io.konveyor.provider;

import io.grpc.stub.StreamObserver;
import io.konveyor.provider.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * gRPC service implementing {@code ProviderCodeLocationService}. Returns source code
 * snippets around a given location, formatted with line numbers and configurable
 * context lines (default 10 above and below).
 */
public class CodeSnipService extends ProviderCodeLocationServiceGrpc.ProviderCodeLocationServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(CodeSnipService.class);

    private final int contextLines;
    private volatile Charset charset = StandardCharsets.UTF_8;

    public CodeSnipService(int contextLines) {
        this.contextLines = contextLines;
    }

    public void setEncoding(String encoding) {
        if (encoding == null || encoding.isEmpty()) return;
        try {
            this.charset = Charset.forName(encoding);
            LOG.info("Using file encoding: {}", this.charset);
        } catch (Exception e) {
            LOG.warn("Unsupported encoding '{}', using UTF-8", encoding);
        }
    }

    @Override
    public void getCodeSnip(GetCodeSnipRequest request, StreamObserver<GetCodeSnipResponse> responseObserver) {
        String fileUri = request.getUri();
        Location loc = request.getCodeLocation();

        try {
            String snippet = extractSnippet(fileUri, (int) loc.getStartPosition().getLine(), contextLines, charset);
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

    String extractSnippet(String fileUri, int targetLine, int contextLines) throws IOException {
        return extractSnippet(fileUri, targetLine, contextLines, charset);
    }

    static String extractSnippet(String fileUri, int targetLine, int contextLines, Charset charset) throws IOException {
        Path path = Path.of(URI.create(fileUri));
        List<String> lines = Files.readAllLines(path, charset);

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

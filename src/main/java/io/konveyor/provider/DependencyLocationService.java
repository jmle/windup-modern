package io.konveyor.provider;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.stub.StreamObserver;
import io.konveyor.provider.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DependencyLocationService
        extends ProviderDependencyLocationServiceGrpc.ProviderDependencyLocationServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyLocationService.class);

    @Override
    public void getDependencyLocation(GetDependencyLocationRequest request,
                                      StreamObserver<GetDependencyLocationResponse> responseObserver) {
        Dependency dep = request.getDep();
        String depFile = request.getDepFile();

        String groupId = extractExtra(dep.getExtras(), "groupId");
        String artifactId = extractExtra(dep.getExtras(), "artifactId");

        if (groupId == null || artifactId == null) {
            LOG.warn("Missing groupId/artifactId in extras for dep {}", dep.getName());
            responseObserver.onNext(emptyResponse());
            responseObserver.onCompleted();
            return;
        }

        try {
            Path filePath = resolveDepFilePath(depFile);
            int line = findDependencyLine(filePath, groupId, artifactId);
            Location location = Location.newBuilder()
                    .setStartPosition(Position.newBuilder().setLine(line).setCharacter(0))
                    .setEndPosition(Position.newBuilder().setLine(line).setCharacter(0))
                    .build();

            responseObserver.onNext(GetDependencyLocationResponse.newBuilder()
                    .setLocation(location)
                    .build());
        } catch (IOException e) {
            LOG.error("Failed to read dep file {}: {}", depFile, e.getMessage());
            responseObserver.onNext(emptyResponse());
        }
        responseObserver.onCompleted();
    }

    static int findDependencyLine(Path depFile, String groupId, String artifactId) throws IOException {
        List<String> lines = Files.readAllLines(depFile);
        String fileName = depFile.getFileName().toString();

        if ("pom.xml".equals(fileName)) {
            return findInPom(lines, groupId, artifactId);
        } else if (fileName.endsWith(".gradle")) {
            return findInGradle(lines, groupId, artifactId);
        }
        return 0;
    }

    static int findInPom(List<String> lines, String groupId, String artifactId) {
        boolean inDependencies = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.contains("<dependencies")) inDependencies = true;
            if (!inDependencies) continue;

            if (xmlTagEquals(line, "groupId", groupId)) {
                for (int j = Math.max(0, i - 3); j <= Math.min(lines.size() - 1, i + 3); j++) {
                    if (j != i && xmlTagEquals(lines.get(j).trim(), "artifactId", artifactId)) {
                        return Math.min(i, j) + 1;
                    }
                }
            }
        }
        return 0;
    }

    private static boolean xmlTagEquals(String line, String tag, String value) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = line.indexOf(open);
        if (start < 0) return false;
        int end = line.indexOf(close, start);
        if (end < 0) return false;
        String content = line.substring(start + open.length(), end).trim();
        return content.equals(value);
    }

    static int findInGradle(List<String> lines, String groupId, String artifactId) {
        String pattern = groupId + ":" + artifactId;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(pattern)) {
                return i + 1;
            }
        }
        return 0;
    }

    static Path resolveDepFilePath(String depFile) {
        if (depFile.startsWith("file://")) {
            return Path.of(URI.create(depFile));
        }
        return Path.of(depFile);
    }

    private static String extractExtra(Struct extras, String key) {
        if (extras == null) return null;
        Value val = extras.getFieldsMap().get(key);
        if (val == null) return null;
        String s = val.getStringValue();
        return s.isEmpty() ? null : s;
    }

    private static GetDependencyLocationResponse emptyResponse() {
        return GetDependencyLocationResponse.newBuilder()
                .setLocation(Location.newBuilder()
                        .setStartPosition(Position.newBuilder().setLine(0).setCharacter(0))
                        .setEndPosition(Position.newBuilder().setLine(0).setCharacter(0)))
                .build();
    }
}

package io.konveyor.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyLocationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void findInPom_locatesGroupIdLine() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>5.3.20</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "org.springframework", "spring-core");
        assertThat(line).isEqualTo(4);
    }

    @Test
    void findInPom_locatesSecondDependency() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "org.slf4j", "slf4j-api");
        assertThat(line).isEqualTo(9);
    }

    @Test
    void findInPom_returnsZeroWhenNotFound() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        int line = DependencyLocationService.findDependencyLine(pom, "com.nonexistent", "missing");
        assertThat(line).isEqualTo(0);
    }

    @Test
    void findInGradle_locatesDependency() throws IOException {
        Path gradle = tempDir.resolve("build.gradle");
        Files.writeString(gradle, """
                plugins {
                    id 'java'
                }
                dependencies {
                    implementation 'org.springframework:spring-core:5.3.20'
                    implementation 'org.slf4j:slf4j-api:2.0.0'
                }
                """);

        int line = DependencyLocationService.findDependencyLine(gradle, "org.slf4j", "slf4j-api");
        assertThat(line).isEqualTo(6);
    }

    @Test
    void findInGradle_returnsZeroWhenNotFound() throws IOException {
        Path gradle = tempDir.resolve("build.gradle");
        Files.writeString(gradle, """
                dependencies {
                    implementation 'org.springframework:spring-core:5.3.20'
                }
                """);

        int line = DependencyLocationService.findDependencyLine(gradle, "com.nonexistent", "missing");
        assertThat(line).isEqualTo(0);
    }

    // ── GetDependencyLocation RPC — regression for the static-parsing path (FAILING_TESTS.md §5b) ──
    // Before the fix, the static-parsing fallback produced Dependency protos without groupId/artifactId
    // in extras, so getDependencyLocation could not resolve a line and incidents landed at pom.xml:0.

    @Test
    void getDependencyLocation_resolvesLineFromExtras() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.13</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // Dependency proto as produced by getDependenciesFromStaticParsing (extras carry the coords)
        io.konveyor.provider.grpc.Dependency dep = io.konveyor.provider.grpc.Dependency.newBuilder()
                .setName("org.slf4j.slf4j-api")
                .setExtras(com.google.protobuf.Struct.newBuilder()
                        .putFields("groupId", com.google.protobuf.Value.newBuilder()
                                .setStringValue("org.slf4j").build())
                        .putFields("artifactId", com.google.protobuf.Value.newBuilder()
                                .setStringValue("slf4j-api").build())
                        .build())
                .build();

        io.konveyor.provider.grpc.Location loc = invokeGetDependencyLocation(dep, pom.toUri().toString());
        // groupId is on line 4 (1-based)
        assertThat(loc.getStartPosition().getLine()).isEqualTo(4);
        assertThat(loc.getEndPosition().getLine()).isEqualTo(4);
    }

    @Test
    void getDependencyLocation_returnsZeroWhenExtrasMissing() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // No groupId/artifactId extras — the pre-fix shape — must degrade to line 0, not crash.
        io.konveyor.provider.grpc.Dependency dep = io.konveyor.provider.grpc.Dependency.newBuilder()
                .setName("org.slf4j.slf4j-api")
                .build();

        io.konveyor.provider.grpc.Location loc = invokeGetDependencyLocation(dep, pom.toUri().toString());
        assertThat(loc.getStartPosition().getLine()).isEqualTo(0);
    }

    private io.konveyor.provider.grpc.Location invokeGetDependencyLocation(
            io.konveyor.provider.grpc.Dependency dep, String depFile) {
        io.konveyor.provider.grpc.GetDependencyLocationRequest request =
                io.konveyor.provider.grpc.GetDependencyLocationRequest.newBuilder()
                        .setDep(dep)
                        .setDepFile(depFile)
                        .build();

        var captured = new java.util.concurrent.atomic.AtomicReference<
                io.konveyor.provider.grpc.GetDependencyLocationResponse>();
        new DependencyLocationService().getDependencyLocation(request,
                new io.grpc.stub.StreamObserver<>() {
                    @Override
                    public void onNext(io.konveyor.provider.grpc.GetDependencyLocationResponse value) {
                        captured.set(value);
                    }
                    @Override public void onError(Throwable t) { throw new RuntimeException(t); }
                    @Override public void onCompleted() { }
                });

        assertThat(captured.get()).isNotNull();
        return captured.get().getLocation();
    }
}

package org.jboss.windup.java.maven;

import org.jboss.windup.java.model.MavenProjectModel;
import org.jboss.windup.model.DependencyModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MavenPomParser}.
 */
class MavenPomParserTest {

    private MavenPomParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new MavenPomParser();
    }

    // ------------------------------------------------------------------
    // Simple POM
    // ------------------------------------------------------------------

    @Test
    void parseSimplePom() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <name>My Application</name>
                    <description>A sample application</description>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getGroupId()).isEqualTo("com.example");
        assertThat(model.getArtifactId()).isEqualTo("my-app");
        assertThat(model.getMavenVersion()).isEqualTo("1.0.0");
        assertThat(model.getVersion()).isEqualTo("1.0.0");
        assertThat(model.getPackaging()).isEqualTo("jar");
        assertThat(model.getName()).isEqualTo("My Application");
        assertThat(model.getDescription()).isEqualTo("A sample application");
        assertThat(model.getProjectType()).isEqualTo("maven");
        assertThat(model.getParentMavenProject()).isNull();
        assertThat(model.getDependencies()).isEmpty();
    }

    @Test
    void parseSimplePom_defaultPackaging() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getPackaging()).isEqualTo("jar");
    }

    @Test
    void parseSimplePom_gav() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.test</groupId>
                    <artifactId>test-lib</artifactId>
                    <version>3.1.0</version>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getGAV()).isEqualTo("org.test:test-lib:3.1.0");
    }

    // ------------------------------------------------------------------
    // POM with parent
    // ------------------------------------------------------------------

    @Test
    void parsePomWithParent() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        // groupId and version inherited from parent
        assertThat(model.getGroupId()).isEqualTo("com.example");
        assertThat(model.getArtifactId()).isEqualTo("child-module");
        assertThat(model.getMavenVersion()).isEqualTo("1.0.0");

        // Parent model
        MavenProjectModel parent = model.getParentMavenProject();
        assertThat(parent).isNotNull();
        assertThat(parent.getGroupId()).isEqualTo("com.example");
        assertThat(parent.getArtifactId()).isEqualTo("parent-pom");
        assertThat(parent.getMavenVersion()).isEqualTo("1.0.0");

        // Also accessible through the generic parent project accessor
        assertThat(model.getParentProject()).isSameAs(parent);
    }

    @Test
    void parsePomWithParent_overrideGroupId() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <groupId>com.example.child</groupId>
                    <artifactId>child-module</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        // Explicit groupId/version override parent
        assertThat(model.getGroupId()).isEqualTo("com.example.child");
        assertThat(model.getMavenVersion()).isEqualTo("2.0.0");
    }

    // ------------------------------------------------------------------
    // POM with dependencies
    // ------------------------------------------------------------------

    @Test
    void parsePomWithDependencies() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>1.18.30</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        List<DependencyModel> deps = model.getDependencies();
        assertThat(deps).hasSize(3);

        assertThat(deps.get(0).groupId()).isEqualTo("org.slf4j");
        assertThat(deps.get(0).artifactId()).isEqualTo("slf4j-api");
        assertThat(deps.get(0).version()).isEqualTo("2.0.9");
        assertThat(deps.get(0).scope()).isNull();
        assertThat(deps.get(0).classifier()).isNull();

        assertThat(deps.get(1).groupId()).isEqualTo("junit");
        assertThat(deps.get(1).artifactId()).isEqualTo("junit");
        assertThat(deps.get(1).version()).isEqualTo("4.13.2");
        assertThat(deps.get(1).scope()).isEqualTo("test");

        assertThat(deps.get(2).groupId()).isEqualTo("org.projectlombok");
        assertThat(deps.get(2).artifactId()).isEqualTo("lombok");
        assertThat(deps.get(2).scope()).isEqualTo("provided");
    }

    @Test
    void parsePomWithDependency_classifier() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>io.netty</groupId>
                            <artifactId>netty-transport-native-epoll</artifactId>
                            <version>4.1.100</version>
                            <classifier>linux-x86_64</classifier>
                        </dependency>
                    </dependencies>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getDependencies()).hasSize(1);
        DependencyModel dep = model.getDependencies().get(0);
        assertThat(dep.classifier()).isEqualTo("linux-x86_64");
    }

    // ------------------------------------------------------------------
    // POM with properties
    // ------------------------------------------------------------------

    @Test
    void parsePomWithProperties() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>props-app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <slf4j.version>2.0.9</slf4j.version>
                        <jackson.version>2.15.3</jackson.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>${slf4j.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>${jackson.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        List<DependencyModel> deps = model.getDependencies();
        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).version()).isEqualTo("2.0.9");
        assertThat(deps.get(1).version()).isEqualTo("2.15.3");
    }

    @Test
    void parsePomWithProjectVersionProperty() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>version-test</artifactId>
                    <version>3.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>${project.groupId}</groupId>
                            <artifactId>sibling-module</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        List<DependencyModel> deps = model.getDependencies();
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).groupId()).isEqualTo("com.example");
        assertThat(deps.get(0).version()).isEqualTo("3.0.0");
    }

    @Test
    void parsePomWithProjectArtifactIdProperty() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>self-ref</artifactId>
                    <version>1.0.0</version>
                    <name>${project.artifactId}</name>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getName()).isEqualTo("self-ref");
    }

    // ------------------------------------------------------------------
    // War packaging
    // ------------------------------------------------------------------

    @Test
    void parsePomWithWarPackaging() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-webapp</artifactId>
                    <version>1.0.0</version>
                    <packaging>war</packaging>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        assertThat(model.getPackaging()).isEqualTo("war");
    }

    // ------------------------------------------------------------------
    // Full POM (parent + properties + dependencies)
    // ------------------------------------------------------------------

    @Test
    void parseFullPom() throws IOException {
        Path pomFile = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.0</version>
                    </parent>
                    <artifactId>demo-service</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                    <packaging>jar</packaging>
                    <name>Demo Service</name>
                    <description>A Spring Boot demo service</description>
                    <properties>
                        <lombok.version>1.18.30</lombok.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        MavenProjectModel model = parser.parse(pomFile);

        // GAV -- version is explicit, groupId inherited from parent
        assertThat(model.getGroupId()).isEqualTo("org.springframework.boot");
        assertThat(model.getArtifactId()).isEqualTo("demo-service");
        assertThat(model.getMavenVersion()).isEqualTo("0.1.0-SNAPSHOT");

        // Parent
        MavenProjectModel parent = model.getParentMavenProject();
        assertThat(parent).isNotNull();
        assertThat(parent.getArtifactId()).isEqualTo("spring-boot-starter-parent");

        // Dependencies with interpolation
        assertThat(model.getDependencies()).hasSize(2);
        assertThat(model.getDependencies().get(0).version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(model.getDependencies().get(1).version()).isEqualTo("1.18.30");
        assertThat(model.getDependencies().get(1).scope()).isEqualTo("provided");

        // Descriptors
        assertThat(model.getName()).isEqualTo("Demo Service");
        assertThat(model.getDescription()).isEqualTo("A Spring Boot demo service");
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    @Test
    void parseInvalidFile_throwsException() throws IOException {
        Path pomFile = tempDir.resolve("bad-pom.xml");
        Files.writeString(pomFile, "this is not xml");

        assertThatThrownBy(() -> parser.parse(pomFile))
                .isInstanceOf(MavenPomParseException.class)
                .hasMessageContaining("Failed to parse POM file");
    }

    @Test
    void parseNonExistentFile_throwsException() {
        Path pomFile = tempDir.resolve("nonexistent.xml");

        assertThatThrownBy(() -> parser.parse(pomFile))
                .isInstanceOf(MavenPomParseException.class)
                .hasMessageContaining("Failed to parse POM file");
    }

    // ------------------------------------------------------------------
    // Equals / hashCode
    // ------------------------------------------------------------------

    @Test
    void parsedModels_equalsByGAV() throws IOException {
        Path pomFile1 = writePom("pom1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>same</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Path pomFile2 = writePom("pom2.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>same</artifactId>
                    <version>1.0.0</version>
                    <name>Different Name</name>
                </project>
                """);

        MavenProjectModel model1 = parser.parse(pomFile1);
        MavenProjectModel model2 = parser.parse(pomFile2);

        assertThat(model1).isEqualTo(model2);
        assertThat(model1.hashCode()).isEqualTo(model2.hashCode());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path writePom(String content) throws IOException {
        return writePom("pom.xml", content);
    }

    private Path writePom(String fileName, String content) throws IOException {
        Path pomFile = tempDir.resolve(fileName);
        Files.writeString(pomFile, content);
        return pomFile;
    }
}

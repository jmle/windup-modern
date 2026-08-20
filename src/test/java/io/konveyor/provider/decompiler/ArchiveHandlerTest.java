package io.konveyor.provider.decompiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ArchiveHandler}: archive explosion, JAR handling (decompile to source),
 * and WAR handling (decompile WEB-INF/classes, extract WEB-INF/lib dependencies).
 * Builds real compiled JARs and WARs from source at test time.
 */
class ArchiveHandlerTest {

    @TempDir
    Path tempDir;

    ArchiveHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ArchiveHandler(new VineflowerDecompiler(1));
    }

    @Test
    void shouldExplodeArchive() throws Exception {
        Path jar = createSimpleJar("test.jar", "com/example/Foo.class");
        Path explodedDir = tempDir.resolve("exploded");
        ArchiveHandler.explode(jar, explodedDir);

        assertThat(explodedDir.resolve("com/example/Foo.class")).exists();
    }

    @Test
    void shouldHandleJarToMavenStructure() throws Exception {
        Path jar = createCompiledJar("test.jar", "Foo");
        Path projectDir = tempDir.resolve("java-project");

        ArchiveHandler.ArchiveResult result = handler.handleArchive(jar, projectDir);

        assertThat(result.archivePath()).isEqualTo(jar);
        assertThat(result.sourceDirs()).isNotEmpty();
        assertThat(result.dependencyJars()).isEmpty();
        assertThat(projectDir.resolve("src/main/java")).isDirectory();
    }

    @Test
    void shouldHandleWarToMavenStructure() throws Exception {
        Path war = createTestWar();
        Path projectDir = tempDir.resolve("java-project");

        ArchiveHandler.ArchiveResult result = handler.handleArchive(war, projectDir);

        assertThat(result.archivePath()).isEqualTo(war);
        assertThat(result.dependencyJars()).isNotEmpty();

        assertThat(projectDir.resolve("src/main/java")).isDirectory();
        assertThat(projectDir.resolve("src/main/webapp/WEB-INF/web.xml")).exists();
    }

    @Test
    void shouldCopyNonClassResourcesFromClasses() throws Exception {
        Path war = createWarWithResources();
        Path projectDir = tempDir.resolve("java-project");

        handler.handleArchive(war, projectDir);

        assertThat(projectDir.resolve("src/main/java/META-INF/persistence.xml")).exists();
        String content = Files.readString(projectDir.resolve("src/main/java/META-INF/persistence.xml"));
        assertThat(content).contains("persistence-unit");
    }

    @Test
    void shouldExtractPomXmlFromMetaInfMaven() throws Exception {
        Path war = createWarWithPom();
        Path projectDir = tempDir.resolve("java-project");

        handler.handleArchive(war, projectDir);

        assertThat(projectDir.resolve("pom.xml")).exists();
        String content = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(content).contains("<groupId>com.test</groupId>");
    }

    @Test
    void shouldCopyWebResources() throws Exception {
        Path war = createWarWithWebResources();
        Path projectDir = tempDir.resolve("java-project");

        handler.handleArchive(war, projectDir);

        assertThat(projectDir.resolve("src/main/webapp/WEB-INF/web.xml")).exists();
        assertThat(projectDir.resolve("src/main/webapp/index.html")).exists();
        assertThat(projectDir.resolve("src/main/webapp/css/style.css")).exists();
    }

    private Path createSimpleJar(String name, String... entries) throws IOException {
        Path jar = tempDir.resolve(name);
        try (FileSystem zipFs = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            for (String entry : entries) {
                Path entryPath = zipFs.getPath(entry);
                if (entryPath.getParent() != null) {
                    Files.createDirectories(entryPath.getParent());
                }
                Files.write(entryPath, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            }
        }
        return jar;
    }

    private Path createCompiledJar(String jarName, String className) throws Exception {
        Path sourceDir = tempDir.resolve("src-" + className);
        Path packageDir = sourceDir.resolve("com/example");
        Files.createDirectories(packageDir);

        Files.writeString(packageDir.resolve(className + ".java"), """
                package com.example;
                public class %s {
                    public String getValue() { return "test"; }
                }
                """.formatted(className));

        Path classesDir = tempDir.resolve("classes-" + className);
        Files.createDirectories(classesDir);

        new ProcessBuilder("javac", "-d", classesDir.toString(),
                packageDir.resolve(className + ".java").toString())
                .redirectErrorStream(true).start().waitFor();

        Path jarPath = tempDir.resolve(jarName);
        new ProcessBuilder("jar", "cf", jarPath.toString(), "-C", classesDir.toString(), ".")
                .redirectErrorStream(true).start().waitFor();

        return jarPath;
    }

    private Path createTestWar() throws Exception {
        Path libJar = createCompiledJar("dep.jar", "DepClass");

        Path warStaging = tempDir.resolve("war-staging");
        Path webInfClasses = warStaging.resolve("WEB-INF/classes/com/example");
        Path webInfLib = warStaging.resolve("WEB-INF/lib");
        Path webInf = warStaging.resolve("WEB-INF");
        Files.createDirectories(webInfClasses);
        Files.createDirectories(webInfLib);

        Files.writeString(webInfClasses.resolve("Servlet.java"), """
                package com.example;
                public class Servlet {
                    public void doGet() {}
                }
                """);

        new ProcessBuilder("javac", "-d", warStaging.resolve("WEB-INF/classes").toString(),
                webInfClasses.resolve("Servlet.java").toString())
                .redirectErrorStream(true).start().waitFor();

        Files.deleteIfExists(webInfClasses.resolve("Servlet.java"));

        Files.copy(libJar, webInfLib.resolve("dep.jar"));

        Files.writeString(webInf.resolve("web.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app><display-name>test</display-name></web-app>
                """);

        Path warPath = tempDir.resolve("test.war");
        new ProcessBuilder("jar", "cf", warPath.toString(), "-C", warStaging.toString(), ".")
                .redirectErrorStream(true).start().waitFor();

        return warPath;
    }

    private Path createWarWithResources() throws Exception {
        Path warStaging = tempDir.resolve("war-resources-staging");
        Path classesDir = warStaging.resolve("WEB-INF/classes/com/example");
        Path metaInf = warStaging.resolve("WEB-INF/classes/META-INF");
        Files.createDirectories(classesDir);
        Files.createDirectories(metaInf);

        Files.writeString(classesDir.resolve("App.java"), """
                package com.example;
                public class App { public void run() {} }
                """);

        new ProcessBuilder("javac", "-d", warStaging.resolve("WEB-INF/classes").toString(),
                classesDir.resolve("App.java").toString())
                .redirectErrorStream(true).start().waitFor();
        Files.deleteIfExists(classesDir.resolve("App.java"));

        Files.writeString(metaInf.resolve("persistence.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence><persistence-unit name="test"/></persistence>
                """);

        Path webInf = warStaging.resolve("WEB-INF");
        Files.writeString(webInf.resolve("web.xml"), "<web-app/>");

        Path warPath = tempDir.resolve("resources.war");
        new ProcessBuilder("jar", "cf", warPath.toString(), "-C", warStaging.toString(), ".")
                .redirectErrorStream(true).start().waitFor();
        return warPath;
    }

    private Path createWarWithPom() throws Exception {
        Path warStaging = tempDir.resolve("war-pom-staging");
        Path classesDir = warStaging.resolve("WEB-INF/classes/com/example");
        Path mavenDir = warStaging.resolve("META-INF/maven/com.test/test-app");
        Files.createDirectories(classesDir);
        Files.createDirectories(mavenDir);

        Files.writeString(classesDir.resolve("App.java"), """
                package com.example;
                public class App { public void run() {} }
                """);

        new ProcessBuilder("javac", "-d", warStaging.resolve("WEB-INF/classes").toString(),
                classesDir.resolve("App.java").toString())
                .redirectErrorStream(true).start().waitFor();
        Files.deleteIfExists(classesDir.resolve("App.java"));

        Files.writeString(mavenDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <groupId>com.test</groupId>
                  <artifactId>test-app</artifactId>
                  <version>1.0</version>
                </project>
                """);

        Path warPath = tempDir.resolve("withpom.war");
        new ProcessBuilder("jar", "cf", warPath.toString(), "-C", warStaging.toString(), ".")
                .redirectErrorStream(true).start().waitFor();
        return warPath;
    }

    private Path createWarWithWebResources() throws Exception {
        Path warStaging = tempDir.resolve("war-web-staging");
        Path classesDir = warStaging.resolve("WEB-INF/classes/com/example");
        Path cssDir = warStaging.resolve("css");
        Files.createDirectories(classesDir);
        Files.createDirectories(cssDir);

        Files.writeString(classesDir.resolve("App.java"), """
                package com.example;
                public class App { public void run() {} }
                """);

        new ProcessBuilder("javac", "-d", warStaging.resolve("WEB-INF/classes").toString(),
                classesDir.resolve("App.java").toString())
                .redirectErrorStream(true).start().waitFor();
        Files.deleteIfExists(classesDir.resolve("App.java"));

        Files.writeString(warStaging.resolve("WEB-INF/web.xml"), "<web-app/>");
        Files.writeString(warStaging.resolve("index.html"), "<html><body>Hello</body></html>");
        Files.writeString(cssDir.resolve("style.css"), "body { color: black; }");

        Path warPath = tempDir.resolve("webresources.war");
        new ProcessBuilder("jar", "cf", warPath.toString(), "-C", warStaging.toString(), ".")
                .redirectErrorStream(true).start().waitFor();
        return warPath;
    }
}

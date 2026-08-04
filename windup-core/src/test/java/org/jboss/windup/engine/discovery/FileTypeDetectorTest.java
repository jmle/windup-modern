package org.jboss.windup.engine.discovery;

import org.jboss.windup.model.ArchiveType;
import org.jboss.windup.model.FileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeDetectorTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "App.java,          JAVA_SOURCE",
            "MyClass.class,     JAVA_CLASS",
            "pom.xml,           XML",
            "schema.xsd,        XML",
            "transform.xslt,    XML",
            "service.wsdl,      XML",
            "taglib.tld,        XML",
            "config.yml,        YAML",
            "config.yaml,       YAML",
            "app.properties,    PROPERTIES",
            "index.jsp,         JSP",
            "page.jspx,         JSP",
            "template.xhtml,    XHTML",
            "index.html,        HTML",
            "page.htm,          HTML",
            "styles.css,        CSS",
            "app.js,            JAVASCRIPT",
            "app.ts,            JAVASCRIPT",
            "schema.sql,        SQL",
            "MANIFEST.MF,       MANIFEST",
            "lib.jar,           ARCHIVE",
            "app.war,           ARCHIVE",
            "enterprise.ear,    ARCHIVE",
            "bundle.zip,        ARCHIVE"
    })
    void detectsFileTypeFromExtension(String fileName, FileType expectedType) {
        FileType detected = FileTypeDetector.detectFileType(Path.of(fileName));
        assertThat(detected).isEqualTo(expectedType);
    }

    @Test
    void returnsOtherForUnknownExtension() {
        assertThat(FileTypeDetector.detectFileType(Path.of("readme.txt"))).isEqualTo(FileType.OTHER);
        assertThat(FileTypeDetector.detectFileType(Path.of("image.png"))).isEqualTo(FileType.OTHER);
        assertThat(FileTypeDetector.detectFileType(Path.of("Makefile"))).isEqualTo(FileType.OTHER);
    }

    @Test
    void handlesNoExtension() {
        assertThat(FileTypeDetector.detectFileType(Path.of("Dockerfile"))).isEqualTo(FileType.OTHER);
        assertThat(FileTypeDetector.detectFileType(Path.of("LICENSE"))).isEqualTo(FileType.OTHER);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(FileTypeDetector.detectFileType(Path.of("App.JAVA"))).isEqualTo(FileType.JAVA_SOURCE);
        assertThat(FileTypeDetector.detectFileType(Path.of("config.XML"))).isEqualTo(FileType.XML);
        assertThat(FileTypeDetector.detectFileType(Path.of("lib.JAR"))).isEqualTo(FileType.ARCHIVE);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "lib.jar,   JAR",
            "app.war,   WAR",
            "app.ear,   EAR",
            "conn.rar,  RAR",
            "svc.sar,   SAR",
            "dist.zip,  ZIP"
    })
    void detectsArchiveTypeFromExtension(String fileName, ArchiveType expectedType) {
        ArchiveType detected = FileTypeDetector.detectArchiveType(Path.of(fileName));
        assertThat(detected).isEqualTo(expectedType);
    }

    @Test
    void returnsOtherArchiveTypeForNonArchive() {
        assertThat(FileTypeDetector.detectArchiveType(Path.of("App.java"))).isEqualTo(ArchiveType.OTHER);
        assertThat(FileTypeDetector.detectArchiveType(Path.of("pom.xml"))).isEqualTo(ArchiveType.OTHER);
    }

    @Test
    void isArchiveDetectsCorrectly() {
        assertThat(FileTypeDetector.isArchive(Path.of("lib.jar"))).isTrue();
        assertThat(FileTypeDetector.isArchive(Path.of("app.war"))).isTrue();
        assertThat(FileTypeDetector.isArchive(Path.of("app.ear"))).isTrue();
        assertThat(FileTypeDetector.isArchive(Path.of("bundle.zip"))).isTrue();
        assertThat(FileTypeDetector.isArchive(Path.of("App.java"))).isFalse();
        assertThat(FileTypeDetector.isArchive(Path.of("config.xml"))).isFalse();
    }

    @Test
    void getExtensionHandlesEdgeCases() {
        assertThat(FileTypeDetector.getExtension(Path.of("file."))).isEmpty();
        assertThat(FileTypeDetector.getExtension(Path.of(".hidden"))).isEqualTo("hidden");
        assertThat(FileTypeDetector.getExtension(Path.of("archive.tar.gz"))).isEqualTo("gz");
    }
}

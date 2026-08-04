package org.jboss.windup.model;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryTest {

    @Test
    void registerAndFindAll() {
        var registry = new ModelRegistry<FileModel>();
        var file1 = new FileModel(Path.of("/app/Foo.java"));
        var file2 = new FileModel(Path.of("/app/Bar.java"));

        registry.register(file1);
        registry.register(file2);

        assertThat(registry.findAll()).containsExactly(file1, file2);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void indexedLookup() {
        var registry = new ModelRegistry<FileModel>();
        registry.addIndex("fileName", FileModel::getFileName);

        var file1 = new FileModel(Path.of("/app/Foo.java"));
        file1.setFileType(FileType.JAVA_SOURCE);
        var file2 = new FileModel(Path.of("/app/Bar.java"));
        file2.setFileType(FileType.JAVA_SOURCE);
        var file3 = new FileModel(Path.of("/app/pom.xml"));
        file3.setFileType(FileType.XML);

        registry.register(file1);
        registry.register(file2);
        registry.register(file3);

        List<FileModel> result = registry.findByIndex("fileName", "Foo.java");
        assertThat(result).containsExactly(file1);

        List<FileModel> xmlFiles = registry.findByIndex("fileName", "pom.xml");
        assertThat(xmlFiles).containsExactly(file3);
    }

    @Test
    void findUniqueByIndex() {
        var registry = new ModelRegistry<FileModel>();
        registry.addIndex("path", f -> f.getFilePath().toString());

        var file = new FileModel(Path.of("/app/Foo.java"));
        registry.register(file);

        Optional<FileModel> found = registry.findUniqueByIndex("path", "/app/Foo.java");
        assertThat(found).isPresent().contains(file);

        Optional<FileModel> notFound = registry.findUniqueByIndex("path", "/app/Missing.java");
        assertThat(notFound).isEmpty();
    }

    @Test
    void indexAddedAfterRegistration() {
        var registry = new ModelRegistry<FileModel>();
        var file = new FileModel(Path.of("/app/Foo.java"));
        file.setFileType(FileType.JAVA_SOURCE);
        registry.register(file);

        registry.addIndex("type", f -> f.getFileType());

        List<FileModel> result = registry.findByIndex("type", FileType.JAVA_SOURCE);
        assertThat(result).containsExactly(file);
    }
}

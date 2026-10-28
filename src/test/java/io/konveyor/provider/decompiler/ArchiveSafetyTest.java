package io.konveyor.provider.decompiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security test (MTA-8158): extracting an untrusted archive must never write outside the target dir
 * (zip-slip). The provider decompiles arbitrary uploaded JAR/WAR/EAR files, so a malicious entry name
 * like {@code ../../evil} must not escape the extraction directory.
 */
class ArchiveSafetyTest {

    @TempDir
    Path tempDir;

    private Path writeJar(String name, String... entries) throws Exception {
        Path jar = tempDir.resolve(name);
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (String e : entries) {
                zos.putNextEntry(new ZipEntry(e));
                zos.write("x".getBytes());
                zos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void explodeRejectsZipSlipAndWritesNothingOutsideTarget() throws Exception {
        Path archive = writeJar("malicious.jar", "ok/good.txt", "../../pwned.txt");
        Path target = tempDir.resolve("extract/here");

        // The ZIP filesystem refuses an entry containing '..', so the whole archive is rejected —
        // and crucially, nothing is written outside the target dir.
        assertThatThrownBy(() -> ArchiveHandler.explode(archive, target))
                .isInstanceOf(Exception.class);

        assertThat(tempDir.resolve("pwned.txt")).doesNotExist();
        assertThat(tempDir.resolve("extract/pwned.txt")).doesNotExist();
    }

    @Test
    void explodeExtractsBenignArchiveWithinTarget() throws Exception {
        Path archive = writeJar("good.jar", "a/b/c.txt", "top.txt");
        Path target = tempDir.resolve("out");

        ArchiveHandler.explode(archive, target);

        assertThat(target.resolve("a/b/c.txt")).exists();
        assertThat(target.resolve("top.txt")).exists();
    }
}

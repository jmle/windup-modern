package io.konveyor.provider.decompiler;

import java.nio.file.Path;

/**
 * Result of decompiling a single JAR, tracking the number of successfully decompiled
 * classes and any errors encountered.
 */
public record DecompileResult(Path jarPath, Path outputDir, int classCount, int errorCount) {

    public boolean hasOutput() {
        return classCount > 0;
    }
}

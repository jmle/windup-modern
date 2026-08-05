package org.jboss.windup.provider.decompiler;

import java.nio.file.Path;

public record DecompileResult(Path jarPath, Path outputDir, int classCount, int errorCount) {

    public boolean hasOutput() {
        return classCount > 0;
    }
}

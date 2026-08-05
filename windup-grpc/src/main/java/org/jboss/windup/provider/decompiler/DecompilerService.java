package org.jboss.windup.provider.decompiler;

import java.nio.file.Path;
import java.util.List;

public interface DecompilerService {

    DecompileResult decompileJar(Path jarPath, Path outputDir);

    List<DecompileResult> decompileJars(List<Path> jarPaths, Path outputDir);
}

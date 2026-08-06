package io.konveyor.provider.decompiler;

import java.nio.file.Path;
import java.util.List;

/**
 * Contract for decompiling Java bytecode. Accepts binary JARs and produces
 * {@code .java} source files in an output directory.
 */
public interface DecompilerService {

    DecompileResult decompileJar(Path jarPath, Path outputDir);

    List<DecompileResult> decompileJars(List<Path> jarPaths, Path outputDir);
}

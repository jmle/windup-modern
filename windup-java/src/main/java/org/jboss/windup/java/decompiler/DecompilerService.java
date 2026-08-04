package org.jboss.windup.java.decompiler;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over Java decompilers (Fernflower, Procyon, or fallback tools).
 *
 * <p>Implementations convert {@code .class} bytecode files back into
 * {@code .java} source text for downstream analysis. When a full decompiler
 * is not available on the classpath the {@link FallbackDecompiler} provides
 * a best-effort disassembly via the JDK's {@code javap} tool.</p>
 */
public interface DecompilerService {

    /**
     * Decompiles a single {@code .class} file and returns the resulting Java
     * source text.
     *
     * @param classFile path to the {@code .class} file
     * @return the decompiled source, or {@link Optional#empty()} if
     *         decompilation failed or is not supported
     */
    Optional<String> decompile(Path classFile);

    /**
     * Decompiles every {@code .class} entry inside a JAR (or ZIP) archive,
     * writing the results into {@code outputDir}.
     *
     * @param archivePath path to the JAR/ZIP archive
     * @param outputDir   directory to write decompiled {@code .java} files into
     * @return a map of fully-qualified class name to decompiled source text
     *         for each successfully decompiled class
     */
    Map<String, String> decompileArchive(Path archivePath, Path outputDir);
}

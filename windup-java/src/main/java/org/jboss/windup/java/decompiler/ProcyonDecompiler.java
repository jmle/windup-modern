package org.jboss.windup.java.decompiler;

import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Decompiler implementation backed by Procyon.
 *
 * <p>This class is marked {@link Alternative} so it is only activated when the
 * Procyon dependency ({@code com.strobel:procyon-compilertools}) is present on
 * the classpath <em>and</em> selected via CDI configuration. When it is not
 * selected the {@link FallbackDecompiler} is used instead.</p>
 *
 * <p>The actual Procyon calls are commented out below because the dependency
 * may not be available at build time. To enable full Procyon decompilation:</p>
 * <ol>
 *   <li>Add the Procyon dependency to the POM.</li>
 *   <li>Uncomment the implementation sections.</li>
 *   <li>Activate this alternative in {@code beans.xml} or via
 *       {@code @Priority}.</li>
 * </ol>
 */
@Alternative
@Singleton
public class ProcyonDecompiler implements DecompilerService {

    private static final Logger LOG = Logger.getLogger(ProcyonDecompiler.class.getName());

    @Override
    public Optional<String> decompile(Path classFile) {
        /*
         * Full Procyon implementation would look like:
         *
         *   DecompilerSettings settings = DecompilerSettings.javaDefaults();
         *   settings.setForceExplicitImports(true);
         *   StringWriter writer = new StringWriter();
         *   Decompiler.decompile(classFile.toString(), new PlainTextOutput(writer), settings);
         *   String source = writer.toString();
         *   return source.isEmpty() ? Optional.empty() : Optional.of(source);
         */
        LOG.warning("Procyon decompiler is not available; "
                + "add com.strobel:procyon-compilertools to the classpath to enable it.");
        return Optional.empty();
    }

    @Override
    public Map<String, String> decompileArchive(Path archivePath, Path outputDir) {
        /*
         * Full Procyon implementation would look like:
         *
         *   DecompilerSettings settings = DecompilerSettings.javaDefaults();
         *   settings.setForceExplicitImports(true);
         *   Map<String, String> results = new LinkedHashMap<>();
         *
         *   try (FileSystem zipFs = FileSystems.newFileSystem(archivePath)) {
         *       Path root = zipFs.getPath("/");
         *       try (Stream<Path> walk = Files.walk(root)) {
         *           walk.filter(p -> p.toString().endsWith(".class"))
         *               .forEach(entry -> {
         *                   String className = classNameFromPath(entry.toString());
         *                   StringWriter writer = new StringWriter();
         *                   Decompiler.decompile(entry.toString(),
         *                           new PlainTextOutput(writer), settings);
         *                   String source = writer.toString();
         *                   if (!source.isEmpty()) {
         *                       results.put(className, source);
         *                       writeToOutputDir(outputDir, className, source);
         *                   }
         *               });
         *       }
         *   }
         *   return results;
         */
        LOG.warning("Procyon decompiler is not available; "
                + "add com.strobel:procyon-compilertools to the classpath to enable it.");
        return Map.of();
    }
}

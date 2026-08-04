package org.jboss.windup.java.decompiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleBuilder;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.java.model.JavaSourceFileModel;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A {@link RuleProvider} that decompiles {@code .class} files discovered
 * during earlier phases into Java source so that downstream analysis rules
 * can inspect the code.
 *
 * <p>Runs in {@link Phase#DECOMPILATION}, after archive extraction. For each
 * {@link FileType#JAVA_CLASS JAVA_CLASS} file that does not already have a
 * corresponding {@code .java} source, the provider invokes the configured
 * {@link DecompilerService} and, on success, registers a new
 * {@link JavaSourceFileModel} in the analysis context.</p>
 */
@ApplicationScoped
public class DecompilationProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(DecompilationProvider.class.getName());

    static final String PROVIDER_ID = "decompilation-provider";

    @Inject
    DecompilerService decompilerService;

    @Override
    public RuleProviderMetadata getMetadata() {
        return new RuleProviderMetadata(
                PROVIDER_ID,
                Phase.DECOMPILATION,
                Set.of("java", "decompilation"),
                Set.of(),
                Set.of(),
                List.of("archive-extraction"),
                List.of()
        );
    }

    @Override
    public List<Rule> getRules() {
        return RuleBuilder.create()
                .addRule("decompile-class-files")
                .when(this::checkForClassFiles)
                .withMetadata(new RuleMetadata(Phase.DECOMPILATION))
                .perform(this::decompileClassFiles)
                .build();
    }

    /**
     * Condition: checks whether the analysis context contains any
     * {@link FileType#JAVA_CLASS} file models.
     */
    ConditionResult checkForClassFiles(AnalysisRun run) {
        List<FileModel> classFiles = run.getContext().getFilesByType(FileType.JAVA_CLASS);
        if (classFiles.isEmpty()) {
            return ConditionResult.noMatch();
        }

        // Filter to only those without a corresponding .java source already registered
        List<FileModel> needsDecompilation = classFiles.stream()
                .filter(fm -> !hasCorrespondingSource(run, fm))
                .collect(Collectors.toList());

        if (needsDecompilation.isEmpty()) {
            return ConditionResult.noMatch();
        }

        return ConditionResult.match(needsDecompilation);
    }

    /**
     * Action: decompiles each matched class file and registers the resulting
     * source in the context.
     */
    @SuppressWarnings("unchecked")
    void decompileClassFiles(AnalysisRun run, ConditionResult matched) {
        Path outputDir = run.getConfiguration().getOutputDirectory().resolve("decompiled");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Cannot create decompilation output directory", e);
            return;
        }

        List<FileModel> classFiles = (List<FileModel>) (List<?>) matched.items();
        int successCount = 0;

        for (FileModel classFileModel : classFiles) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled, stopping decompilation");
                break;
            }

            Path classFilePath = classFileModel.getFilePath();
            Optional<String> source = decompilerService.decompile(classFilePath);

            if (source.isPresent()) {
                Path sourceFile = writeDecompiledSource(outputDir, classFileModel, source.get());
                if (sourceFile != null) {
                    JavaSourceFileModel jsf = new JavaSourceFileModel(sourceFile);
                    jsf.setPackageName(inferPackageName(classFileModel));
                    run.getContext().files().register(jsf);
                    successCount++;
                }
            }
        }

        LOG.info("Decompiled " + successCount + " of " + classFiles.size() + " class files");
    }

    /**
     * Checks whether a {@code .java} source file already exists for the given
     * class file model.
     */
    private boolean hasCorrespondingSource(AnalysisRun run, FileModel classFile) {
        String classFileName = classFile.getFileName();
        if (classFileName == null || !classFileName.endsWith(".class")) {
            return false;
        }
        String sourceFileName = classFileName.replace(".class", ".java");
        List<FileModel> matches = run.getContext().files()
                .findByIndex("fileName", sourceFileName);
        return !matches.isEmpty();
    }

    /**
     * Writes the decompiled source text into the output directory, mirroring
     * the original class file's relative structure.
     */
    private Path writeDecompiledSource(Path outputDir, FileModel classFile, String source) {
        try {
            String baseName = classFile.getFileName().replace(".class", ".java");
            Path target = outputDir.resolve(baseName);
            Files.createDirectories(target.getParent());
            Files.writeString(target, source);
            return target;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write decompiled source for "
                    + classFile.getFileName(), e);
            return null;
        }
    }

    /**
     * Infers a package name from the class file's directory structure.
     * Returns an empty string if the package cannot be determined.
     */
    private String inferPackageName(FileModel classFile) {
        Path parent = classFile.getFilePath().getParent();
        if (parent == null) {
            return "";
        }
        // Attempt to derive a package name from the directory path by looking
        // for common root markers (e.g. com, org, net).
        String dirStr = parent.toString().replace('\\', '/');
        for (String root : List.of("/com/", "/org/", "/net/", "/io/", "/javax/", "/jakarta/")) {
            int idx = dirStr.indexOf(root);
            if (idx >= 0) {
                return dirStr.substring(idx + 1).replace('/', '.');
            }
        }
        return "";
    }
}

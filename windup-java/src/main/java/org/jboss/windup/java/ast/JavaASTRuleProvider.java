package org.jboss.windup.java.ast;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.java.model.JavaClassModel;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.java.model.JavaSourceFileModel;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.jboss.windup.model.ModelRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that performs AST parsing of Java source files
 * discovered during the {@link Phase#DISCOVERY} phase.
 *
 * <p>For each {@link FileType#JAVA_SOURCE} file in the analysis context, this
 * provider reads the source code, parses it with {@link JavaASTParser}, and
 * registers the resulting {@link JavaClassModel} instances in a dedicated
 * {@link ModelRegistry} on the {@link AnalysisContext}. It also collects
 * type references using {@link TypeReferenceCollector} and stores them
 * in a registry keyed by {@link JavaClassReference}.</p>
 *
 * <p>Runs in {@link Phase#INITIAL_ANALYSIS}, after {@code FileDiscoveryProvider}.</p>
 */
@ApplicationScoped
public class JavaASTRuleProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(JavaASTRuleProvider.class.getName());

    private static final RuleProviderMetadata METADATA = new RuleProviderMetadata(
            "JavaASTRuleProvider",
            Phase.INITIAL_ANALYSIS,
            Set.of(),
            Set.of(),
            Set.of(),
            List.of("FileDiscoveryProvider"),
            List.of()
    );

    @Inject
    JavaASTParser parser;

    /**
     * No-arg constructor for CDI proxy creation.
     */
    public JavaASTRuleProvider() {
    }

    /**
     * Constructor for manual / test usage.
     */
    public JavaASTRuleProvider(JavaASTParser parser) {
        this.parser = parser;
    }

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "java-ast-parse",
                        this::checkJavaSourcesExist,
                        this::parseJavaSources,
                        new RuleMetadata(Phase.INITIAL_ANALYSIS)
                )
        );
    }

    /**
     * Condition: returns the list of JAVA_SOURCE FileModels if any exist.
     */
    ConditionResult checkJavaSourcesExist(AnalysisRun run) {
        List<FileModel> javaFiles = run.getContext().getFilesByType(FileType.JAVA_SOURCE);
        if (javaFiles.isEmpty()) {
            return ConditionResult.noMatch();
        }
        return ConditionResult.match(javaFiles);
    }

    /**
     * Action: for each matched JAVA_SOURCE file, read the source, parse it,
     * and register the resulting models in the context.
     */
    void parseJavaSources(AnalysisRun run, ConditionResult matched) {
        AnalysisContext context = run.getContext();
        ModelRegistry<JavaClassModel> javaClasses = context.getOrCreateRegistry(JavaClassModel.class);
        ModelRegistry<JavaClassReference> javaReferences = context.getOrCreateRegistry(JavaClassReference.class);

        for (Object item : matched.items()) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled during Java AST parsing");
                return;
            }

            if (!(item instanceof FileModel fileModel)) {
                continue;
            }

            try {
                String sourceCode = Files.readString(fileModel.getFilePath(), StandardCharsets.UTF_8);

                // Parse the source file
                JavaSourceFileModel sourceFileModel = parser.parse(fileModel.getFilePath(), sourceCode);

                // Register all discovered classes
                for (JavaClassModel classModel : sourceFileModel.getJavaClasses()) {
                    javaClasses.register(classModel);
                }

                // Collect type references
                List<JavaClassReference> refs = collectTypeReferences(sourceCode, fileModel);
                for (JavaClassReference ref : refs) {
                    javaReferences.register(ref);
                }

                LOG.fine(() -> "Parsed " + fileModel.getFilePath() + ": "
                        + sourceFileModel.getJavaClasses().size() + " classes, "
                        + refs.size() + " type references");

            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read Java source file: " + fileModel.getFilePath(), e);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to parse Java source file: " + fileModel.getFilePath(), e);
            }
        }
    }

    /**
     * Parses the source and runs the {@link TypeReferenceCollector}.
     */
    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            "org.eclipse.jdt.core.compiler.source", "17",
            "org.eclipse.jdt.core.compiler.compliance", "17",
            "org.eclipse.jdt.core.compiler.codegen.targetPlatform", "17"
    );

    private List<JavaClassReference> collectTypeReferences(String sourceCode, FileModel fileModel) {
        ASTParser astParser = ASTParser.newParser(AST.JLS17);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        astParser.setSource(sourceCode.toCharArray());
        astParser.setCompilerOptions(COMPILER_OPTIONS);
        astParser.setResolveBindings(false);

        CompilationUnit cu = (CompilationUnit) astParser.createAST(null);

        TypeReferenceCollector collector = new TypeReferenceCollector(cu, fileModel);
        cu.accept(collector);
        return collector.getReferences();
    }
}

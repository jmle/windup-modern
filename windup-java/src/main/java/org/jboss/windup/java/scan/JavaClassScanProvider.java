package org.jboss.windup.java.scan;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.java.model.JavaClassModel;
import org.jboss.windup.java.model.JavaClassReference;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.jboss.windup.model.ModelRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that scans Java {@code .class} files (bytecode) to
 * extract class references and type information using {@link ClassFileScanner}.
 *
 * <p>This provider runs in {@link Phase#INITIAL_ANALYSIS} after file discovery
 * has populated the {@link AnalysisContext} with {@link FileModel} instances.
 * For each file of type {@link FileType#JAVA_CLASS}, it:</p>
 * <ol>
 *   <li>Runs the {@link ClassFileScanner} to parse the class file constant pool</li>
 *   <li>Creates a {@link JavaClassModel} with the discovered class metadata</li>
 *   <li>Creates {@link JavaClassReference} instances for type references (inheritance,
 *       method calls, field accesses)</li>
 *   <li>Registers all created models in the {@link AnalysisContext} custom registries</li>
 * </ol>
 */
@ApplicationScoped
public class JavaClassScanProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(JavaClassScanProvider.class.getName());

    private static final RuleProviderMetadata METADATA = new RuleProviderMetadata(
            "JavaClassScanProvider",
            Phase.INITIAL_ANALYSIS,
            Set.of(),
            Set.of(),
            Set.of(),
            List.of("file-discovery"),
            List.of()
    );

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "java-class-scan",
                        this::checkForClassFiles,
                        this::scanClassFiles,
                        new RuleMetadata(
                                Phase.INITIAL_ANALYSIS,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                List.of("file-discovery"),
                                List.of()
                        )
                )
        );
    }

    /**
     * Condition: checks whether the context contains any {@link FileType#JAVA_CLASS} files.
     */
    ConditionResult checkForClassFiles(AnalysisRun run) {
        List<FileModel> classFiles = run.getContext().getFilesByType(FileType.JAVA_CLASS);
        if (classFiles.isEmpty()) {
            return ConditionResult.noMatch();
        }
        return ConditionResult.match(classFiles);
    }

    /**
     * Action: scans each {@code .class} file and creates models from the results.
     */
    void scanClassFiles(AnalysisRun run, ConditionResult matched) {
        AnalysisContext context = run.getContext();
        ModelRegistry<JavaClassModel> classRegistry = context.getOrCreateRegistry(JavaClassModel.class);
        ModelRegistry<JavaClassReference> referenceRegistry = context.getOrCreateRegistry(JavaClassReference.class);

        @SuppressWarnings("unchecked")
        List<FileModel> classFiles = (List<FileModel>) (List<?>) matched.items();

        for (FileModel fileModel : classFiles) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled during class file scanning");
                return;
            }

            try {
                ClassFileScanResult result = ClassFileScanner.scan(fileModel.getFilePath());
                createModels(result, fileModel, classRegistry, referenceRegistry);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to scan class file: " + fileModel.getFilePath(), e);
            }
        }

        LOG.info("Scanned " + classRegistry.size() + " class files, found "
                + referenceRegistry.size() + " type references");
    }

    /**
     * Creates {@link JavaClassModel} and {@link JavaClassReference} instances from
     * a {@link ClassFileScanResult} and registers them in the appropriate registries.
     */
    private void createModels(ClassFileScanResult result, FileModel fileModel,
                              ModelRegistry<JavaClassModel> classRegistry,
                              ModelRegistry<JavaClassReference> referenceRegistry) {
        // Create the JavaClassModel
        JavaClassModel classModel = new JavaClassModel(result.className());
        classModel.setSuperClassName(result.superClassName());
        classModel.getInterfaces().addAll(result.interfaces());
        classModel.setSourceFileModel(fileModel);
        classRegistry.register(classModel);

        // Create inheritance references
        if (result.superClassName() != null && !"java.lang.Object".equals(result.superClassName())) {
            JavaClassReference superRef = new JavaClassReference(
                    result.superClassName(),
                    JavaClassReference.ReferenceType.INHERITANCE,
                    0, 0);
            superRef.setSourceFile(fileModel);
            referenceRegistry.register(superRef);
        }

        // Create interface implementation references
        for (String iface : result.interfaces()) {
            JavaClassReference ifaceRef = new JavaClassReference(
                    iface,
                    JavaClassReference.ReferenceType.IMPLEMENTS_TYPE,
                    0, 0);
            ifaceRef.setSourceFile(fileModel);
            referenceRegistry.register(ifaceRef);
        }

        // Create method call references
        for (ClassFileScanResult.MethodReference methodRef : result.methodReferences()) {
            // Skip references to the class itself (internal method calls) and constructors
            // of the same class to reduce noise
            if (methodRef.className().equals(result.className()) && "<init>".equals(methodRef.methodName())) {
                continue;
            }

            JavaClassReference.ReferenceType refType;
            if ("<init>".equals(methodRef.methodName())) {
                refType = JavaClassReference.ReferenceType.CONSTRUCTOR_CALL;
            } else {
                refType = JavaClassReference.ReferenceType.METHOD_CALL;
            }

            JavaClassReference ref = new JavaClassReference(
                    methodRef.className() + "." + methodRef.methodName(),
                    refType,
                    0, 0);
            ref.setSourceFile(fileModel);
            referenceRegistry.register(ref);
        }

        // Create field access references
        for (ClassFileScanResult.FieldReference fieldRef : result.fieldReferences()) {
            JavaClassReference ref = new JavaClassReference(
                    fieldRef.className() + "." + fieldRef.fieldName(),
                    JavaClassReference.ReferenceType.FIELD_DECLARATION,
                    0, 0);
            ref.setSourceFile(fileModel);
            referenceRegistry.register(ref);
        }

        // Create type references for all referenced classes
        for (String referencedClass : result.referencedClasses()) {
            // Skip self-references and common JDK types to reduce noise
            if (referencedClass.equals(result.className())) {
                continue;
            }

            JavaClassReference ref = new JavaClassReference(
                    referencedClass,
                    JavaClassReference.ReferenceType.TYPE,
                    0, 0);
            ref.setSourceFile(fileModel);
            referenceRegistry.register(ref);
        }
    }
}

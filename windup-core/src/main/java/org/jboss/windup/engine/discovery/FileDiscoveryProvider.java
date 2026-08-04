package org.jboss.windup.engine.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.ApplicationModel;
import org.jboss.windup.model.ArchiveModel;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;
import org.jboss.windup.model.ProjectModel;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that discovers files from the configured input paths and registers
 * {@link FileModel} instances in the {@link AnalysisContext}.
 * <p>
 * For each input path this provider:
 * <ul>
 *   <li>Creates an {@link ApplicationModel} and a root {@link ProjectModel}</li>
 *   <li>Walks the directory tree using {@link Files#walkFileTree}</li>
 *   <li>Creates a {@link FileModel} for each file and directory encountered</li>
 *   <li>Detects {@link FileType} from extension via {@link FileTypeDetector}</li>
 *   <li>Creates an {@link ArchiveModel} instead for archive files (.jar, .war, .ear, .zip, etc.)</li>
 *   <li>Computes SHA-1 and MD5 hashes for regular files via {@link HashCalculator}</li>
 *   <li>Sets parent-child directory relationships on the models</li>
 * </ul>
 * <p>
 * Runs in {@link Phase#DISCOVERY}.
 */
@ApplicationScoped
public class FileDiscoveryProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(FileDiscoveryProvider.class.getName());

    private static final RuleProviderMetadata METADATA =
            new RuleProviderMetadata("FileDiscoveryProvider", Phase.DISCOVERY);

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "file-discovery",
                        run -> ConditionResult.match(run.getConfiguration().getInputPaths()),
                        this::discoverFiles,
                        new RuleMetadata(Phase.DISCOVERY)
                )
        );
    }

    /**
     * Performs the file discovery for all input paths.
     */
    void discoverFiles(AnalysisRun run, ConditionResult matched) {
        List<Path> inputPaths = run.getConfiguration().getInputPaths();
        AnalysisContext context = run.getContext();

        for (Path inputPath : inputPaths) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled during file discovery");
                return;
            }

            if (!Files.exists(inputPath)) {
                LOG.warning("Input path does not exist: " + inputPath);
                continue;
            }

            // Create application and project models for this input
            ApplicationModel application = createApplication(inputPath, context);
            ProjectModel project = createProject(inputPath, context, application);

            // Walk the file tree
            walkInputPath(inputPath, context, project, run);
        }
    }

    private ApplicationModel createApplication(Path inputPath, AnalysisContext context) {
        ApplicationModel application = new ApplicationModel();
        String name = inputPath.getFileName() != null ? inputPath.getFileName().toString() : inputPath.toString();
        application.setName(name);
        application.getInputPaths().add(inputPath);
        context.applications().register(application);
        return application;
    }

    private ProjectModel createProject(Path inputPath, AnalysisContext context, ApplicationModel application) {
        ProjectModel project = new ProjectModel();
        String name = inputPath.getFileName() != null ? inputPath.getFileName().toString() : inputPath.toString();
        project.setName(name);

        // Create the root file model for the project
        FileModel rootFile = createFileModel(inputPath, context, null);
        project.setRootFileModel(rootFile);
        rootFile.setProject(project);

        application.getProjectModels().add(project);
        context.projects().register(project);
        return project;
    }

    private void walkInputPath(Path inputPath, AnalysisContext context, ProjectModel project, AnalysisRun run) {
        // Map to track directory FileModels for parent-child relationships
        Map<Path, FileModel> directoryModels = new HashMap<>();

        // The root itself is already registered; add it to the directory map
        context.getFileByPath(inputPath).ifPresent(rootModel -> directoryModels.put(inputPath, rootModel));

        try {
            Files.walkFileTree(inputPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (run.isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }

                    // Skip the root -- it is already registered
                    if (dir.equals(inputPath)) {
                        return FileVisitResult.CONTINUE;
                    }

                    FileModel parentModel = directoryModels.get(dir.getParent());
                    FileModel dirModel = createFileModel(dir, context, parentModel);
                    dirModel.setProject(project);
                    project.getFileModels().add(dirModel);
                    directoryModels.put(dir, dirModel);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (run.isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }

                    FileModel parentModel = directoryModels.get(file.getParent());
                    FileModel fileModel = createFileModel(file, context, parentModel);
                    fileModel.setProject(project);
                    project.getFileModels().add(fileModel);

                    // Compute hashes for regular files
                    try {
                        HashCalculator.computeHashes(fileModel);
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Failed to compute hashes for: " + file, e);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.log(Level.WARNING, "Failed to visit file: " + file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error walking input path: " + inputPath, e);
        }
    }

    private FileModel createFileModel(Path path, AnalysisContext context, FileModel parent) {
        FileModel model;

        if (Files.isDirectory(path)) {
            model = new FileModel(path);
            model.setDirectory(true);
            model.setFileType(FileType.DIRECTORY);
        } else if (FileTypeDetector.isArchive(path)) {
            ArchiveModel archive = new ArchiveModel(path);
            archive.setArchiveType(FileTypeDetector.detectArchiveType(path));
            context.archives().register(archive);
            model = archive;
        } else {
            model = new FileModel(path);
            model.setFileType(FileTypeDetector.detectFileType(path));
        }

        try {
            if (Files.isRegularFile(path)) {
                model.setFileSize(Files.size(path));
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read file size: " + path, e);
        }

        if (parent != null) {
            model.setParentDirectory(parent);
        }

        context.files().register(model);
        return model;
    }
}

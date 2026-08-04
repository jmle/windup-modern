package org.jboss.windup.java.maven;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.windup.engine.AnalysisRun;
import org.jboss.windup.engine.ConditionResult;
import org.jboss.windup.engine.Phase;
import org.jboss.windup.engine.Rule;
import org.jboss.windup.engine.RuleMetadata;
import org.jboss.windup.engine.RuleProvider;
import org.jboss.windup.engine.RuleProviderMetadata;
import org.jboss.windup.java.model.MavenProjectModel;
import org.jboss.windup.model.AnalysisContext;
import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link RuleProvider} that discovers Maven POM files ({@code pom.xml}) in
 * the analysis context and creates {@link MavenProjectModel} instances from
 * them.
 *
 * <p>For each {@code pom.xml} found in the file registry, this provider:</p>
 * <ul>
 *   <li>Parses the POM using {@link MavenPomParser}</li>
 *   <li>Creates a {@link MavenProjectModel} with GAV coordinates, parent info
 *       and dependency list</li>
 *   <li>Links the project model to the parent directory's {@link FileModel}</li>
 *   <li>Registers the project model in the {@link AnalysisContext} project
 *       registry</li>
 * </ul>
 *
 * <p>Runs in {@link Phase#COMPOSITION}, after {@code FileDiscoveryProvider}.</p>
 */
@ApplicationScoped
public class MavenAnalysisProvider implements RuleProvider {

    private static final Logger LOG = Logger.getLogger(MavenAnalysisProvider.class.getName());

    private static final RuleProviderMetadata METADATA =
            new RuleProviderMetadata(
                    "MavenAnalysisProvider",
                    Phase.COMPOSITION,
                    java.util.Set.of("maven"),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    List.of("FileDiscoveryProvider"),
                    List.of()
            );

    private final MavenPomParser parser = new MavenPomParser();

    @Override
    public RuleProviderMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<Rule> getRules() {
        return List.of(
                new Rule(
                        "maven-pom-analysis",
                        this::findPomFiles,
                        this::analyzePomFiles,
                        new RuleMetadata(Phase.COMPOSITION)
                )
        );
    }

    /**
     * Condition: checks whether there are XML {@link FileModel}s named
     * {@code pom.xml} in the analysis context.
     */
    ConditionResult findPomFiles(AnalysisRun run) {
        AnalysisContext context = run.getContext();
        List<FileModel> xmlFiles = context.files().findByIndex("type", FileType.XML);
        List<FileModel> pomFiles = xmlFiles.stream()
                .filter(f -> "pom.xml".equals(f.getFileName()))
                .toList();

        if (pomFiles.isEmpty()) {
            return ConditionResult.noMatch();
        }
        return ConditionResult.match(pomFiles);
    }

    /**
     * Action: parses each matched POM file and creates a
     * {@link MavenProjectModel} in the context.
     */
    @SuppressWarnings("unchecked")
    void analyzePomFiles(AnalysisRun run, ConditionResult matched) {
        List<FileModel> pomFiles = (List<FileModel>) (List<?>) matched.items();
        AnalysisContext context = run.getContext();

        for (FileModel pomFile : pomFiles) {
            if (run.isCancelled()) {
                LOG.info("Analysis cancelled during Maven POM processing");
                return;
            }

            try {
                MavenProjectModel mavenProject = parser.parse(pomFile.getFilePath());

                // Link the project model to the parent directory of pom.xml
                FileModel parentDir = pomFile.getParentDirectory();
                if (parentDir != null) {
                    mavenProject.setRootFileModel(parentDir);
                    parentDir.setProject(mavenProject);
                }

                // Also associate the pom.xml file itself with the project
                pomFile.setProject(mavenProject);
                mavenProject.getFileModels().add(pomFile);

                // Register in the general project registry
                context.projects().register(mavenProject);

                // Also register in a Maven-specific registry for targeted lookups
                context.getOrCreateRegistry(MavenProjectModel.class).register(mavenProject);

                LOG.fine("Parsed Maven project: " + mavenProject.getGAV());
            } catch (MavenPomParseException e) {
                LOG.log(Level.WARNING, "Failed to parse POM: " + pomFile.getFilePath(), e);
            }
        }
    }
}

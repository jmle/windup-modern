package org.jboss.windup.cli;

import jakarta.inject.Inject;
import org.jboss.windup.engine.AnalysisConfiguration;
import org.jboss.windup.engine.WindupProcessor;
import org.jboss.windup.model.AnalysisContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

@Command(
        name = "windup",
        mixinStandardHelpOptions = true,
        version = "Windup 7.0.0-SNAPSHOT",
        description = "Application modernization and migration analysis engine"
)
public class WindupCommand implements Runnable {

    @Inject
    WindupProcessor processor;

    @Option(names = {"--input", "-i"}, description = "Input application path(s)", required = true)
    List<Path> inputPaths;

    @Option(names = {"--output", "-o"}, description = "Output report directory", required = true)
    Path outputDirectory;

    @Option(names = {"--source", "-s"}, description = "Source technology")
    List<String> sourceTechnologies;

    @Option(names = {"--target", "-t"}, description = "Target technology")
    List<String> targetTechnologies;

    @Option(names = "--packages", description = "Packages to include")
    List<String> includePackages;

    @Option(names = "--excludePackages", description = "Packages to exclude")
    List<String> excludePackages;

    @Option(names = {"--userRulesDirectory", "-r"}, description = "Path(s) to user-provided rule directories")
    List<Path> userRulesDirectories;

    @Option(names = "--sourceMode", description = "Analyze source code (not binaries)")
    boolean sourceMode;

    @Option(names = "--exportCSV", description = "Export results to CSV")
    boolean exportCSV;

    @Override
    public void run() {
        var builder = AnalysisConfiguration.builder()
                .outputDirectory(outputDirectory)
                .sourceMode(sourceMode)
                .exportCSV(exportCSV);

        inputPaths.forEach(builder::inputPath);
        if (sourceTechnologies != null) sourceTechnologies.forEach(builder::sourceTechnology);
        if (targetTechnologies != null) targetTechnologies.forEach(builder::targetTechnology);
        if (includePackages != null) includePackages.forEach(builder::includePackage);
        if (excludePackages != null) excludePackages.forEach(builder::excludePackage);
        if (userRulesDirectories != null) userRulesDirectories.forEach(builder::userRulesPath);

        AnalysisContext result = processor.execute(builder.build());

        System.out.println("Analysis complete. Files: " + result.files().size()
                + ", Projects: " + result.projects().size());
        System.out.println("Report: " + outputDirectory.resolve("index.html"));
    }
}

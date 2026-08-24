package io.konveyor.provider;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.konveyor.provider.grpc.Config;
import io.konveyor.provider.grpc.IncidentContext;
import io.konveyor.provider.grpc.ProviderEvaluateResponse;
import io.konveyor.provider.index.LocationType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;

public class ProviderRepl {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -cp ... ProviderRepl <project-path> [options]");
            System.err.println();
            System.err.println("  project-path     Path to a source directory, WAR, JAR, or EAR file");
            System.err.println("  --mode <mode>    Analysis mode: full|source-only (default: full)");
            System.err.println("  --maven-index <path>  Path to maven-index.txt for SHA-1 lookups");
            System.err.println("  --labels <path>  Path to maven.default.index for dep labeling");
            System.exit(1);
        }

        String location = args[0];
        String mode = "full";
        String mavenIndexPath = null;
        String labelsFile = null;
        for (int i = 1; i < args.length; i++) {
            if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i];
            } else if ("--maven-index".equals(args[i]) && i + 1 < args.length) {
                mavenIndexPath = args[++i];
            } else if ("--labels".equals(args[i]) && i + 1 < args.length) {
                labelsFile = args[++i];
            }
        }

        System.out.println("Loading project: " + location);
        System.out.println("Mode: " + mode);
        if (mavenIndexPath != null) System.out.println("Maven index: " + mavenIndexPath);
        System.out.println();

        Struct.Builder specificConfig = Struct.newBuilder();
        if (mavenIndexPath != null) {
            specificConfig.putFields("mavenIndexPath",
                    Value.newBuilder().setStringValue(mavenIndexPath).build());
        }
        if (labelsFile != null) {
            specificConfig.putFields("depOpenSourceLabelsFile",
                    Value.newBuilder().setStringValue(labelsFile).build());
        }

        Config config = Config.newBuilder()
                .setLocation(location)
                .setAnalysisMode(mode)
                .setProviderSpecificConfig(specificConfig)
                .build();

        WorkspaceContext ctx = new WorkspaceContext(1, location, mode, config, 10);
        ctx.index();

        int symbolCount = ctx.getSymbolIndex().size();
        int depCount = ctx.getResolvedDeps().size();
        System.out.println("Indexed " + symbolCount + " symbols, " + depCount + " dependencies");
        System.out.println();
        printHelp();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        System.out.print("\n> ");
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                System.out.print("> ");
                continue;
            }
            if ("quit".equals(line) || "exit".equals(line)) break;
            if ("help".equals(line)) {
                printHelp();
                System.out.print("\n> ");
                continue;
            }
            if ("deps".equals(line)) {
                printDependencies(ctx);
                System.out.print("\n> ");
                continue;
            }
            if ("locations".equals(line)) {
                printLocations();
                System.out.print("\n> ");
                continue;
            }

            try {
                if (line.startsWith("dep:")) {
                    handleDependencyQuery(ctx, line.substring(4).trim());
                } else {
                    handleReferencedQuery(ctx, line);
                }
            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
            }
            System.out.print("\n> ");
        }
        System.out.println("Bye.");
    }

    private static void handleReferencedQuery(WorkspaceContext ctx, String input) {
        String pattern;
        String locationStr = "";

        int atIdx = input.lastIndexOf('@');
        if (atIdx > 0) {
            pattern = input.substring(0, atIdx).trim();
            locationStr = input.substring(atIdx + 1).trim();
        } else {
            pattern = input.trim();
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("referenced:\n");
        yaml.append("  pattern: \"").append(escapeYaml(pattern)).append("\"\n");
        if (!locationStr.isEmpty()) {
            yaml.append("  location: ").append(locationStr.toLowerCase()).append("\n");
        }

        ProviderEvaluateResponse resp = ctx.evaluate("referenced", yaml.toString());
        printResponse(resp, locationStr);
    }

    private static void handleDependencyQuery(WorkspaceContext ctx, String input) {
        String name = null;
        String nameRegex = null;
        String lowerbound = null;
        String upperbound = null;

        for (String part : input.split("\\s+")) {
            if (part.startsWith("lower:")) {
                lowerbound = part.substring(6);
            } else if (part.startsWith("upper:")) {
                upperbound = part.substring(6);
            } else if (part.contains("*") || part.contains("(") || part.contains("|")) {
                nameRegex = part;
            } else {
                name = part;
            }
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("dependency:\n");
        if (name != null) {
            yaml.append("  name: \"").append(escapeYaml(name)).append("\"\n");
        }
        if (nameRegex != null) {
            yaml.append("  name_regex: \"").append(escapeYaml(nameRegex)).append("\"\n");
        }
        if (lowerbound != null) {
            yaml.append("  lowerbound: \"").append(lowerbound).append("\"\n");
        }
        if (upperbound != null) {
            yaml.append("  upperbound: \"").append(upperbound).append("\"\n");
        }

        ProviderEvaluateResponse resp = ctx.evaluate("dependency", yaml.toString());
        if (!resp.getMatched()) {
            System.out.println("  No matching dependencies.");
            return;
        }

        System.out.println("  " + resp.getIncidentContextsCount() + " match(es):");
        for (IncidentContext ic : resp.getIncidentContextsList()) {
            Map<String, Value> vars = ic.getVariables().getFieldsMap();
            String depName = vars.containsKey("name") ? vars.get("name").getStringValue() : "?";
            String version = vars.containsKey("version") ? vars.get("version").getStringValue() : "";
            String file = shortenUri(ic.getFileURI());
            long lineNum = ic.getLineNumber();

            System.out.printf("  %-50s %s%s%n",
                    depName + ":" + version,
                    file,
                    lineNum > 0 ? ":" + lineNum : "");
        }
    }

    private static void printResponse(ProviderEvaluateResponse resp, String locationStr) {
        if (!resp.getMatched()) {
            System.out.println("  No matches.");
            return;
        }

        System.out.println("  " + resp.getIncidentContextsCount() + " match(es):");
        for (IncidentContext ic : resp.getIncidentContextsList()) {
            Map<String, Value> vars = ic.getVariables().getFieldsMap();
            String kind = vars.containsKey("kind") ? vars.get("kind").getStringValue() : "";
            String name = vars.containsKey("name") ? vars.get("name").getStringValue() : "";
            String pkg = vars.containsKey("package") ? vars.get("package").getStringValue() : "";
            String file = shortenUri(ic.getFileURI());
            long lineNum = ic.getLineNumber();

            System.out.printf("  %-12s %-40s %s:%d%n", kind, name, file, lineNum);
        }
    }

    private static void printDependencies(WorkspaceContext ctx) {
        var deps = ctx.getResolvedDeps();
        if (deps.isEmpty()) {
            System.out.println("  No dependencies resolved.");
            return;
        }
        System.out.println("  " + deps.size() + " dependencies:");
        for (var dep : deps) {
            System.out.printf("  %-50s %s%n",
                    dep.groupId() + ":" + dep.artifactId() + ":" + dep.version(),
                    dep.indirect() ? "(transitive)" : "");
        }
    }

    private static void printLocations() {
        System.out.println("  Available locations:");
        for (LocationType lt : LocationType.values()) {
            System.out.println("    " + lt.name().toLowerCase());
        }
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  <pattern>                     Search with default location");
        System.out.println("  <pattern>@<location>          Search with specific location");
        System.out.println("  dep:<name>                    Dependency query (exact name)");
        System.out.println("  dep:<regex>                   Dependency query (regex if contains *|()" + ")");
        System.out.println("  dep:<name> lower:<v> upper:<v>  Dependency with version bounds");
        System.out.println("  deps                          List all resolved dependencies");
        System.out.println("  locations                     List available location types");
        System.out.println("  help                          Show this help");
        System.out.println("  exit                          Quit");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  javax.persistence*@import");
        System.out.println("  org.springframework.context.annotation.Configuration@annotation");
        System.out.println("  javax.servlet.http.HttpServlet@inheritance");
        System.out.println("  dep:org.springframework.spring-beans");
        System.out.println("  dep:org.springframework.* lower:3.0");
    }

    private static String shortenUri(String uri) {
        if (uri == null) return "?";
        int idx = uri.lastIndexOf('/');
        return idx >= 0 ? uri.substring(idx + 1) : uri;
    }

    private static String escapeYaml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

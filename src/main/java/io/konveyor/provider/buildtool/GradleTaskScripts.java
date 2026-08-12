package io.konveyor.provider.buildtool;

/**
 * Embedded Groovy task scripts for Gradle dependency resolution. Two variants exist:
 * one for Gradle 4-8 (uses {@code resolvedConfiguration} API) and one for Gradle 9+
 * (moves resolution to configuration time due to cross-project access restrictions).
 */
final class GradleTaskScripts {

    private GradleTaskScripts() {}

    static final String RESOLVE_DEPS_V8 = """
            task konveyorResolveDependencies {
                doLast {
                    def configs = ['compileClasspath', 'runtimeClasspath', 'implementation',
                                   'api', 'compile', 'runtime']
                    allprojects { proj ->
                        configs.each { configName ->
                            def config = null
                            try { config = proj.configurations.getByName(configName) }
                            catch (Exception e) { return }
                            if (!config.canBeResolved) return
                            try {
                                config.resolvedConfiguration.lenientConfiguration.artifacts.each { a ->
                                    println "RESOLVED: ${a.moduleVersion.id.group}:${a.moduleVersion.id.name}:${a.moduleVersion.id.version}"
                                }
                            } catch (Exception e) {
                                println "WARN: Could not resolve ${configName} for ${proj.name}: ${e.message}"
                            }
                        }
                    }
                }
            }
            """;

    static final String RESOLVE_DEPS_V9 = """
            def resolvedArtifacts = []
            def configs = ['compileClasspath', 'runtimeClasspath', 'implementation', 'api']
            allprojects { proj ->
                configs.each { configName ->
                    def config = null
                    try { config = proj.configurations.getByName(configName) }
                    catch (Exception e) { return }
                    if (!config.canBeResolved) return
                    try {
                        config.incoming.artifactView { view -> view.lenient(true) }.artifacts.each { a ->
                            def id = a.id.componentIdentifier
                            if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                resolvedArtifacts.add("${id.group}:${id.module}:${id.version}")
                            }
                        }
                    } catch (Exception e) { }
                }
            }
            task konveyorResolveDependencies {
                doLast {
                    resolvedArtifacts.each { println "RESOLVED: ${it}" }
                }
            }
            """;

    static final String DOWNLOAD_SOURCES_V8 = """
            task konveyorDownloadSources {
                doLast {
                    def downloadDir = new File(project.buildDir, "downloads")
                    downloadDir.mkdirs()
                    allprojects { proj ->
                        def config = null
                        try { config = proj.configurations.getByName('compileClasspath') }
                        catch (Exception e) { return }
                        if (!config.canBeResolved) return
                        try {
                            config.resolvedConfiguration.lenientConfiguration.artifacts.each { a ->
                                def mid = a.moduleVersion.id
                                def srcDep = proj.dependencies.create("${mid.group}:${mid.name}:${mid.version}:sources@jar")
                                def srcConf = proj.configurations.detachedConfiguration(srcDep)
                                srcConf.transitive = false
                                try {
                                    def files = srcConf.resolve()
                                    files.each { f -> f.renameTo(new File(downloadDir, f.name)) }
                                    println "Found ${files.size()} sources for ${mid.group}:${mid.name}:${mid.version}"
                                } catch (Exception e) {
                                    println "Found 0 sources for ${mid.group}:${mid.name}:${mid.version}"
                                }
                            }
                        } catch (Exception e) { }
                    }
                }
            }
            """;

    static final String DOWNLOAD_SOURCES_V9 = """
            def sourceTargets = []
            def configs = ['compileClasspath', 'runtimeClasspath']
            allprojects { proj ->
                configs.each { configName ->
                    def config = null
                    try { config = proj.configurations.getByName(configName) }
                    catch (Exception e) { return }
                    if (!config.canBeResolved) return
                    try {
                        config.incoming.artifactView { view -> view.lenient(true) }.artifacts.each { a ->
                            def id = a.id.componentIdentifier
                            if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                sourceTargets.add([group: id.group, name: id.module, version: id.version])
                            }
                        }
                    } catch (Exception e) { }
                }
            }
            task konveyorDownloadSources {
                doLast {
                    def downloadDir = new File(project.buildDir, "downloads")
                    downloadDir.mkdirs()
                    sourceTargets.unique().each { t ->
                        def srcDep = project.dependencies.create("${t.group}:${t.name}:${t.version}:sources@jar")
                        def srcConf = project.configurations.detachedConfiguration(srcDep)
                        srcConf.transitive = false
                        try {
                            def files = srcConf.resolve()
                            files.each { f ->
                                def dest = new File(downloadDir, f.name)
                                if (!dest.exists()) f.renameTo(dest)
                            }
                            println "Found ${files.size()} sources for ${t.group}:${t.name}:${t.version}"
                        } catch (Exception e) {
                            println "Found 0 sources for ${t.group}:${t.name}:${t.version}"
                        }
                    }
                }
            }
            """;
}

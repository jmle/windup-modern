package io.konveyor.provider.buildtool;

/**
 * Embedded Gradle task scripts, delivered as <b>init scripts</b> ({@code --init-script}) rather than
 * appended to the project's build file. Init scripts are always Groovy and apply to the build
 * regardless of the build file's DSL, so these work for both Groovy ({@code build.gradle}) and
 * Kotlin ({@code build.gradle.kts}) projects — appending Groovy to a {@code .kts} file is a Kotlin
 * compile error. Each task is registered on {@code allprojects} and resolves its own project's
 * configurations, so invoking the unqualified task name runs it across the root and every subproject.
 *
 * <p>Resolution uses the lenient {@code incoming.artifactView} API, which is stable across Gradle
 * 7.x–9.x. The only version-sensitive part is the build directory: Gradle 9 removed
 * {@code project.buildDir} in favour of {@code project.layout.buildDirectory}.</p>
 */
final class GradleTaskScripts {

    private GradleTaskScripts() {}

    /** DSL-agnostic resolve task (warms the dependency cache); unified across Gradle versions. */
    static final String RESOLVE_DEPS = """
            allprojects {
                tasks.register("konveyorResolveDependencies") {
                    doLast {
                        ['compileClasspath', 'runtimeClasspath'].each { configName ->
                            def config = configurations.findByName(configName)
                            if (config == null || !config.canBeResolved) return
                            try {
                                config.incoming.artifactView { view -> view.lenient(true) }.artifacts.each { a ->
                                    def id = a.id.componentIdentifier
                                    if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                        println "RESOLVED: ${id.group}:${id.module}:${id.version}"
                                    }
                                }
                            } catch (Exception e) { }
                        }
                    }
                }
            }
            """;

    /**
     * DSL-agnostic dependency-SOURCE download task. Identical across versions except for the build
     * directory expression, which {@code gradle9} selects (Gradle 9 removed {@code project.buildDir}).
     */
    static String downloadSourcesInit(boolean gradle9) {
        String buildDir = gradle9 ? "project.layout.buildDirectory.get().asFile" : "project.buildDir";
        return """
                allprojects {
                    tasks.register("konveyorDownloadSources") {
                        doLast {
                            def downloadDir = new File(%s, "downloads")
                            downloadDir.mkdirs()
                            def targets = [] as Set
                            ['compileClasspath', 'runtimeClasspath'].each { configName ->
                                def config = configurations.findByName(configName)
                                if (config == null || !config.canBeResolved) return
                                try {
                                    config.incoming.artifactView { view -> view.lenient(true) }.artifacts.each { a ->
                                        def id = a.id.componentIdentifier
                                        if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                            targets.add([group: id.group, name: id.module, version: id.version])
                                        }
                                    }
                                } catch (Exception e) { }
                            }
                            targets.each { t ->
                                def srcDep = dependencies.create("${t.group}:${t.name}:${t.version}:sources@jar")
                                def srcConf = configurations.detachedConfiguration(srcDep)
                                srcConf.transitive = false
                                try {
                                    def files = srcConf.resolve()
                                    files.each { f ->
                                        def dest = new File(downloadDir, f.name)
                                        if (!dest.exists()) dest.bytes = f.bytes  // copy (renameTo fails from the cache)
                                    }
                                    println "Found ${files.size()} sources for ${t.group}:${t.name}:${t.version}"
                                } catch (Exception e) {
                                    println "Found 0 sources for ${t.group}:${t.name}:${t.version}"
                                }
                            }
                        }
                    }
                }
                """.formatted(buildDir);
    }
}

package org.jboss.windup.model;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AnalysisContext {

    private final ModelRegistry<FileModel> files;
    private final ModelRegistry<ArchiveModel> archives;
    private final ModelRegistry<ProjectModel> projects;
    private final ModelRegistry<ApplicationModel> applications;
    private final Map<Class<?>, ModelRegistry<?>> customRegistries = new HashMap<>();

    public AnalysisContext() {
        this.files = new ModelRegistry<>();
        this.archives = new ModelRegistry<>();
        this.projects = new ModelRegistry<>();
        this.applications = new ModelRegistry<>();

        files.addIndex("path", f -> f.getFilePath().toString());
        files.addIndex("type", FileModel::getFileType);
        files.addIndex("fileName", FileModel::getFileName);
    }

    public ModelRegistry<FileModel> files() { return files; }
    public ModelRegistry<ArchiveModel> archives() { return archives; }
    public ModelRegistry<ProjectModel> projects() { return projects; }
    public ModelRegistry<ApplicationModel> applications() { return applications; }

    /**
     * Returns a typed {@link ModelRegistry} for the given model class, creating
     * one on first access. This allows modules (e.g. windup-java) to store
     * their own model types without the core module depending on them.
     */
    @SuppressWarnings("unchecked")
    public <T> ModelRegistry<T> getOrCreateRegistry(Class<T> type) {
        return (ModelRegistry<T>) customRegistries.computeIfAbsent(type, k -> new ModelRegistry<>());
    }

    public Optional<FileModel> getFileByPath(Path path) {
        return files.findUniqueByIndex("path", path.toString());
    }

    public List<FileModel> getFilesByType(FileType type) {
        return files.findByIndex("type", type);
    }
}

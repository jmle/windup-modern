package org.jboss.windup.model;

import java.util.ArrayList;
import java.util.List;

public class ProjectModel {

    private String name;
    private String version;
    private String projectType;
    private String description;
    private FileModel rootFileModel;
    private ProjectModel parentProject;
    private final List<ProjectModel> childProjects = new ArrayList<>();
    private final List<DependencyModel> dependencies = new ArrayList<>();
    private final List<FileModel> fileModels = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public FileModel getRootFileModel() { return rootFileModel; }
    public void setRootFileModel(FileModel rootFileModel) { this.rootFileModel = rootFileModel; }
    public ProjectModel getParentProject() { return parentProject; }
    public void setParentProject(ProjectModel parentProject) { this.parentProject = parentProject; }
    public List<ProjectModel> getChildProjects() { return childProjects; }
    public List<DependencyModel> getDependencies() { return dependencies; }
    public List<FileModel> getFileModels() { return fileModels; }
}

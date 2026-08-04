package org.jboss.windup.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ApplicationModel {

    private String name;
    private final List<Path> inputPaths = new ArrayList<>();
    private final List<ProjectModel> projectModels = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Path> getInputPaths() { return inputPaths; }
    public List<ProjectModel> getProjectModels() { return projectModels; }
}

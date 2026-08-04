package org.jboss.windup.java.model;

import java.util.Objects;

import org.jboss.windup.model.ProjectModel;

/**
 * Extends {@link ProjectModel} with Maven-specific metadata.
 *
 * <p>Modernized from the legacy graph-backed {@code MavenProjectModel}. The
 * Maven GAV (groupId, artifactId, version) coordinates are stored alongside
 * the general project information inherited from {@link ProjectModel}.</p>
 *
 * <p>Note: the Maven version is stored in {@link #getMavenVersion()} to
 * distinguish it from the generic {@link ProjectModel#getVersion()} field,
 * which may be set independently.</p>
 */
public class MavenProjectModel extends ProjectModel {

    private String groupId;
    private String artifactId;
    private String mavenVersion;
    private String packaging;
    private MavenProjectModel parentMavenProject;

    public MavenProjectModel() {
        setProjectType("maven");
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    /**
     * Returns the version as declared in the Maven POM. This may differ from
     * {@link #getVersion()} if the generic version field is set independently.
     */
    public String getMavenVersion() {
        return mavenVersion;
    }

    public void setMavenVersion(String mavenVersion) {
        this.mavenVersion = mavenVersion;
    }

    /**
     * Returns the Maven packaging type (e.g. {@code jar}, {@code war},
     * {@code ear}, {@code pom}).
     */
    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    /**
     * Returns the parent Maven project as declared via {@code <parent>} in the
     * POM, or {@code null} if there is no parent.
     */
    public MavenProjectModel getParentMavenProject() {
        return parentMavenProject;
    }

    public void setParentMavenProject(MavenProjectModel parentMavenProject) {
        this.parentMavenProject = parentMavenProject;
    }

    /**
     * Returns the Maven GAV coordinate string ({@code groupId:artifactId:version}).
     */
    public String getGAV() {
        return groupId + ":" + artifactId + ":" + mavenVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MavenProjectModel that)) return false;
        return Objects.equals(groupId, that.groupId)
                && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(mavenVersion, that.mavenVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, mavenVersion);
    }

    @Override
    public String toString() {
        return "MavenProjectModel{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", mavenVersion='" + mavenVersion + '\'' +
                ", packaging='" + packaging + '\'' +
                '}';
    }
}

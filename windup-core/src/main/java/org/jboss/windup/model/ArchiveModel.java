package org.jboss.windup.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ArchiveModel extends FileModel {

    private ArchiveType archiveType = ArchiveType.OTHER;
    private final List<FileModel> entries = new ArrayList<>();
    private String organizationName;
    private boolean identified;
    private boolean ignored;
    private String identifiedGroupId;
    private String identifiedArtifactId;
    private String identifiedVersion;

    public ArchiveModel(Path filePath) {
        super(filePath);
        setFileType(FileType.ARCHIVE);
    }

    public ArchiveType getArchiveType() { return archiveType; }
    public void setArchiveType(ArchiveType archiveType) { this.archiveType = archiveType; }
    public List<FileModel> getEntries() { return entries; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public boolean isIdentified() { return identified; }
    public void setIdentified(boolean identified) { this.identified = identified; }
    public boolean isIgnored() { return ignored; }
    public void setIgnored(boolean ignored) { this.ignored = ignored; }
    public String getIdentifiedGroupId() { return identifiedGroupId; }
    public void setIdentifiedGroupId(String identifiedGroupId) { this.identifiedGroupId = identifiedGroupId; }
    public String getIdentifiedArtifactId() { return identifiedArtifactId; }
    public void setIdentifiedArtifactId(String identifiedArtifactId) { this.identifiedArtifactId = identifiedArtifactId; }
    public String getIdentifiedVersion() { return identifiedVersion; }
    public void setIdentifiedVersion(String identifiedVersion) { this.identifiedVersion = identifiedVersion; }
}

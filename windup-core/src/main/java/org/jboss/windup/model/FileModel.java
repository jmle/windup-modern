package org.jboss.windup.model;

import java.nio.file.Path;

public class FileModel {

    private final Path filePath;
    private String fileName;
    private FileType fileType;
    private String sha1Hash;
    private String md5Hash;
    private long fileSize;
    private boolean directory;
    private FileModel parentDirectory;
    private ProjectModel project;

    public FileModel(Path filePath) {
        this.filePath = filePath;
        this.fileName = filePath.getFileName() != null ? filePath.getFileName().toString() : "";
        this.fileType = FileType.OTHER;
    }

    public Path getFilePath() { return filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    public String getSha1Hash() { return sha1Hash; }
    public void setSha1Hash(String sha1Hash) { this.sha1Hash = sha1Hash; }
    public String getMd5Hash() { return md5Hash; }
    public void setMd5Hash(String md5Hash) { this.md5Hash = md5Hash; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public boolean isDirectory() { return directory; }
    public void setDirectory(boolean directory) { this.directory = directory; }
    public FileModel getParentDirectory() { return parentDirectory; }
    public void setParentDirectory(FileModel parentDirectory) { this.parentDirectory = parentDirectory; }
    public ProjectModel getProject() { return project; }
    public void setProject(ProjectModel project) { this.project = project; }
}

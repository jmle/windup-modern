package org.jboss.windup.java.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jboss.windup.model.FileModel;
import org.jboss.windup.model.FileType;

/**
 * Represents a Java source ({@code .java}) file discovered during analysis.
 *
 * <p>Extends the core {@link FileModel} with Java-specific metadata: the
 * declared package, the classes defined in the file, and the import
 * statements.</p>
 *
 * <p>Modernized from the legacy graph-backed {@code JavaSourceFileModel} and
 * {@code AbstractJavaSourceModel} into a concrete class.</p>
 */
public class JavaSourceFileModel extends FileModel {

    private String packageName;
    private final List<JavaClassModel> javaClasses = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();

    public JavaSourceFileModel(Path filePath) {
        super(filePath);
        setFileType(FileType.JAVA_SOURCE);
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Returns the Java classes declared in this source file. The list is
     * mutable; callers may add or remove entries directly.
     */
    public List<JavaClassModel> getJavaClasses() {
        return javaClasses;
    }

    /**
     * Returns the import statements in this source file (fully-qualified type
     * names or wildcard imports such as {@code "java.util.*"}). The list is
     * mutable.
     */
    public List<String> getImports() {
        return imports;
    }

    /**
     * Returns a human-readable path within the project, using the package
     * and class name when available.
     */
    public String getPrettyPathWithinProject() {
        if (packageName != null && !javaClasses.isEmpty()) {
            JavaClassModel primary = javaClasses.get(0);
            return primary.getQualifiedName();
        }
        return getFileName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaSourceFileModel that)) return false;
        return Objects.equals(getFilePath(), that.getFilePath());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFilePath());
    }

    @Override
    public String toString() {
        return "JavaSourceFileModel{" +
                "filePath=" + getFilePath() +
                ", packageName='" + packageName + '\'' +
                ", classCount=" + javaClasses.size() +
                '}';
    }
}

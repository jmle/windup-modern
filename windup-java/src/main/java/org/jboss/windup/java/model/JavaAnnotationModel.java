package org.jboss.windup.java.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a Java annotation instance discovered during analysis.
 *
 * <p>Modernized from the legacy graph-backed {@code JavaAnnotationTypeReferenceModel}
 * and its value sub-models into a flat POJO. Annotation element values are stored
 * in a simple {@code Map<String, Object>} where values may be {@code String},
 * {@code Number}, {@code Boolean}, {@code List}, or nested
 * {@code JavaAnnotationModel} instances.</p>
 */
public final class JavaAnnotationModel {

    private String annotationType;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public JavaAnnotationModel() {
    }

    public JavaAnnotationModel(String annotationType) {
        this.annotationType = annotationType;
    }

    public String getAnnotationType() {
        return annotationType;
    }

    public void setAnnotationType(String annotationType) {
        this.annotationType = annotationType;
    }

    /**
     * Returns the annotation element values. The returned map is mutable;
     * callers may add or remove entries directly.
     */
    public Map<String, Object> getValues() {
        return values;
    }

    /**
     * Convenience method to set a single annotation element value.
     */
    public void setValue(String key, Object value) {
        values.put(key, value);
    }

    /**
     * Returns an unmodifiable view of the annotation element values.
     */
    public Map<String, Object> getValuesUnmodifiable() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaAnnotationModel that)) return false;
        return Objects.equals(annotationType, that.annotationType)
                && Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(annotationType, values);
    }

    @Override
    public String toString() {
        return "JavaAnnotationModel{" +
                "annotationType='" + annotationType + '\'' +
                ", values=" + values +
                '}';
    }
}

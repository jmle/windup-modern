package org.jboss.windup.java.scan;

import java.util.List;
import java.util.Set;

/**
 * Contains the results of scanning a single {@code .class} file's constant pool
 * and class structure. This is a lightweight representation of the type references
 * found in compiled Java bytecode, without performing full decompilation.
 *
 * @param className         the fully-qualified name of the class (e.g. {@code com.example.Foo})
 * @param superClassName    the fully-qualified name of the superclass, or {@code null} for {@code java.lang.Object} itself
 * @param interfaces        the fully-qualified names of implemented interfaces
 * @param referencedClasses all classes referenced anywhere in the constant pool
 * @param methodReferences  methods referenced (invoked or declared) in the constant pool
 * @param fieldReferences   fields referenced (accessed or declared) in the constant pool
 */
public record ClassFileScanResult(
        String className,
        String superClassName,
        List<String> interfaces,
        Set<String> referencedClasses,
        List<MethodReference> methodReferences,
        List<FieldReference> fieldReferences
) {

    /**
     * A method reference found in the constant pool. This covers both
     * {@code CONSTANT_Methodref} and {@code CONSTANT_InterfaceMethodref} entries.
     *
     * @param className  the fully-qualified name of the class owning the method
     * @param methodName the method name
     * @param descriptor the JVM method descriptor (e.g. {@code (Ljava/lang/String;)V})
     */
    public record MethodReference(String className, String methodName, String descriptor) {
    }

    /**
     * A field reference found in the constant pool ({@code CONSTANT_Fieldref}).
     *
     * @param className the fully-qualified name of the class owning the field
     * @param fieldName the field name
     * @param descriptor the JVM field descriptor (e.g. {@code Ljava/lang/String;})
     */
    public record FieldReference(String className, String fieldName, String descriptor) {
    }
}

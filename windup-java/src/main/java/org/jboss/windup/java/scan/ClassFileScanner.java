package org.jboss.windup.java.scan;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A lightweight scanner that reads Java {@code .class} files and extracts type
 * references from the constant pool without performing full decompilation.
 *
 * <p>This scanner parses the class file format as specified in the
 * <a href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html">JVM Specification, Chapter 4</a>.
 * It reads the magic number, version info, constant pool, access flags, this-class,
 * super-class, and interfaces, and then extracts all type references found in
 * {@code CONSTANT_Class}, {@code CONSTANT_Fieldref}, {@code CONSTANT_Methodref},
 * and {@code CONSTANT_InterfaceMethodref} entries.</p>
 *
 * <p>This is a utility class with no CDI annotations; it can be used standalone
 * or from within a rule provider.</p>
 */
public final class ClassFileScanner {

    private static final Logger LOG = Logger.getLogger(ClassFileScanner.class.getName());

    // Class file magic number
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    // Constant pool tag values (JVM Spec 4.4)
    private static final int CONSTANT_Utf8 = 1;
    private static final int CONSTANT_Integer = 3;
    private static final int CONSTANT_Float = 4;
    private static final int CONSTANT_Long = 5;
    private static final int CONSTANT_Double = 6;
    private static final int CONSTANT_Class = 7;
    private static final int CONSTANT_String = 8;
    private static final int CONSTANT_Fieldref = 9;
    private static final int CONSTANT_Methodref = 10;
    private static final int CONSTANT_InterfaceMethodref = 11;
    private static final int CONSTANT_NameAndType = 12;
    private static final int CONSTANT_MethodHandle = 15;
    private static final int CONSTANT_MethodType = 16;
    private static final int CONSTANT_Dynamic = 17;
    private static final int CONSTANT_InvokeDynamic = 18;
    private static final int CONSTANT_Module = 19;
    private static final int CONSTANT_Package = 20;

    private ClassFileScanner() {
        // utility class
    }

    /**
     * Scans the given {@code .class} file and returns a {@link ClassFileScanResult}
     * containing the class name, superclass, interfaces, and all type references
     * extracted from the constant pool.
     *
     * @param classFilePath the path to a {@code .class} file
     * @return the scan result
     * @throws IOException if the file cannot be read or is not a valid class file
     */
    public static ClassFileScanResult scan(Path classFilePath) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(classFilePath)))) {
            return parseClassFile(dis);
        }
    }

    /**
     * Scans class file bytes and returns a {@link ClassFileScanResult}.
     *
     * @param classFileBytes the raw class file bytes
     * @return the scan result
     * @throws IOException if the bytes are not a valid class file
     */
    public static ClassFileScanResult scan(byte[] classFileBytes) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new java.io.ByteArrayInputStream(classFileBytes))) {
            return parseClassFile(dis);
        }
    }

    private static ClassFileScanResult parseClassFile(DataInputStream dis) throws IOException {
        // 1. Magic number
        int magic = dis.readInt();
        if (magic != CLASS_FILE_MAGIC) {
            throw new IOException("Not a valid class file: bad magic number 0x"
                    + Integer.toHexString(magic));
        }

        // 2. Version (minor, major) -- read but not stored
        dis.readUnsignedShort(); // minor_version
        dis.readUnsignedShort(); // major_version

        // 3. Constant pool
        int constantPoolCount = dis.readUnsignedShort();
        ConstantPool pool = readConstantPool(dis, constantPoolCount);

        // 4. Access flags -- read but not stored for now
        dis.readUnsignedShort(); // access_flags

        // 5. This class
        int thisClassIndex = dis.readUnsignedShort();
        String className = pool.resolveClassName(thisClassIndex);

        // 6. Super class
        int superClassIndex = dis.readUnsignedShort();
        String superClassName = superClassIndex == 0 ? null : pool.resolveClassName(superClassIndex);

        // 7. Interfaces
        int interfaceCount = dis.readUnsignedShort();
        List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int i = 0; i < interfaceCount; i++) {
            int ifaceIndex = dis.readUnsignedShort();
            interfaces.add(pool.resolveClassName(ifaceIndex));
        }

        // 8. Extract all referenced classes, method refs, and field refs from the pool
        Set<String> referencedClasses = new LinkedHashSet<>();
        List<ClassFileScanResult.MethodReference> methodReferences = new ArrayList<>();
        List<ClassFileScanResult.FieldReference> fieldReferences = new ArrayList<>();

        pool.extractReferences(referencedClasses, methodReferences, fieldReferences);

        return new ClassFileScanResult(
                className,
                superClassName,
                List.copyOf(interfaces),
                Set.copyOf(referencedClasses),
                List.copyOf(methodReferences),
                List.copyOf(fieldReferences)
        );
    }

    /**
     * Reads the constant pool entries from the class file stream.
     */
    private static ConstantPool readConstantPool(DataInputStream dis, int count) throws IOException {
        // constant_pool indices are 1-based; index 0 is unused
        int[] tags = new int[count];
        String[] utf8Values = new String[count];
        int[][] intPairs = new int[count][]; // for entries that reference two indices

        for (int i = 1; i < count; i++) {
            int tag = dis.readUnsignedByte();
            tags[i] = tag;

            switch (tag) {
                case CONSTANT_Utf8 -> utf8Values[i] = dis.readUTF();
                case CONSTANT_Integer, CONSTANT_Float -> dis.readInt();
                case CONSTANT_Long, CONSTANT_Double -> {
                    dis.readLong();
                    // Long and Double take two constant pool entries
                    i++;
                }
                case CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType,
                     CONSTANT_Module, CONSTANT_Package ->
                    intPairs[i] = new int[]{ dis.readUnsignedShort() };
                case CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
                     CONSTANT_NameAndType, CONSTANT_Dynamic, CONSTANT_InvokeDynamic ->
                    intPairs[i] = new int[]{ dis.readUnsignedShort(), dis.readUnsignedShort() };
                case CONSTANT_MethodHandle -> {
                    dis.readUnsignedByte();  // reference_kind
                    intPairs[i] = new int[]{ dis.readUnsignedShort() };
                }
                default ->
                    throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
            }
        }

        return new ConstantPool(tags, utf8Values, intPairs);
    }

    /**
     * Internal representation of the constant pool, providing methods to resolve
     * names and extract references.
     */
    private record ConstantPool(int[] tags, String[] utf8Values, int[][] intPairs) {

        /**
         * Resolves a CONSTANT_Class entry to a fully-qualified class name
         * (with dots instead of slashes).
         */
        String resolveClassName(int classInfoIndex) {
            if (classInfoIndex <= 0 || classInfoIndex >= tags.length) {
                return "<unknown>";
            }
            if (tags[classInfoIndex] != CONSTANT_Class) {
                return "<unknown>";
            }
            int nameIndex = intPairs[classInfoIndex][0];
            String internalName = resolveUtf8(nameIndex);
            return internalNameToQualified(internalName);
        }

        /**
         * Resolves a CONSTANT_Utf8 entry.
         */
        String resolveUtf8(int index) {
            if (index <= 0 || index >= utf8Values.length || utf8Values[index] == null) {
                return "<unknown>";
            }
            return utf8Values[index];
        }

        /**
         * Resolves a CONSTANT_NameAndType entry, returning {name, descriptor}.
         */
        String[] resolveNameAndType(int natIndex) {
            if (natIndex <= 0 || natIndex >= tags.length || tags[natIndex] != CONSTANT_NameAndType) {
                return new String[]{ "<unknown>", "<unknown>" };
            }
            int nameIdx = intPairs[natIndex][0];
            int descIdx = intPairs[natIndex][1];
            return new String[]{ resolveUtf8(nameIdx), resolveUtf8(descIdx) };
        }

        /**
         * Iterates through the constant pool and collects all class references,
         * method references, and field references.
         */
        void extractReferences(Set<String> referencedClasses,
                               List<ClassFileScanResult.MethodReference> methodRefs,
                               List<ClassFileScanResult.FieldReference> fieldRefs) {
            for (int i = 1; i < tags.length; i++) {
                switch (tags[i]) {
                    case CONSTANT_Class -> {
                        String name = resolveClassName(i);
                        if (!name.startsWith("[") && !name.equals("<unknown>")) {
                            referencedClasses.add(name);
                        } else if (name.startsWith("[")) {
                            // Array type -- extract the element type if it's an object
                            String element = extractArrayElementType(name);
                            if (element != null) {
                                referencedClasses.add(element);
                            }
                        }
                    }
                    case CONSTANT_Fieldref -> {
                        if (intPairs[i] != null && intPairs[i].length == 2) {
                            String ownerClass = resolveClassName(intPairs[i][0]);
                            String[] nat = resolveNameAndType(intPairs[i][1]);
                            fieldRefs.add(new ClassFileScanResult.FieldReference(
                                    ownerClass, nat[0], nat[1]));
                            if (!ownerClass.equals("<unknown>")) {
                                referencedClasses.add(ownerClass);
                            }
                        }
                    }
                    case CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                        if (intPairs[i] != null && intPairs[i].length == 2) {
                            String ownerClass = resolveClassName(intPairs[i][0]);
                            String[] nat = resolveNameAndType(intPairs[i][1]);
                            methodRefs.add(new ClassFileScanResult.MethodReference(
                                    ownerClass, nat[0], nat[1]));
                            if (!ownerClass.equals("<unknown>")) {
                                referencedClasses.add(ownerClass);
                            }
                        }
                    }
                    default -> {
                        // other constant pool entry types are not class references
                    }
                }
            }
        }

        /**
         * Extracts the element type from an array descriptor.
         * For example, {@code "[Ljava/lang/String;"} yields {@code "java.lang.String"}.
         * Returns {@code null} for primitive arrays or unrecognized descriptors.
         */
        private String extractArrayElementType(String arrayClassName) {
            // The className from CONSTANT_Class for arrays looks like "[Lcom/example/Foo;"
            // Strip leading '[' characters
            String desc = arrayClassName;
            while (desc.startsWith("[")) {
                desc = desc.substring(1);
            }
            // Internal name uses '/' -- convert via the utf8 we already resolved.
            // But the resolveClassName already converted '/' to '.', so we handle
            // the L...;  form on the dot-separated version as well.
            if (desc.startsWith("L") && desc.endsWith(";")) {
                return desc.substring(1, desc.length() - 1);
            }
            // It was an array descriptor stored in internal form (slashes)
            // that resolveClassName already converted dots -- check again
            if (desc.contains(".") && !desc.startsWith("L")) {
                return desc;
            }
            // Primitive array (e.g. [I, [B) -- no class reference to extract
            return null;
        }
    }

    /**
     * Converts a JVM internal name (using {@code /} as separator) to a
     * fully-qualified Java name (using {@code .} as separator).
     * Also handles array descriptors like {@code [Ljava/lang/String;}.
     */
    static String internalNameToQualified(String internalName) {
        if (internalName == null) {
            return "<unknown>";
        }
        // Array descriptors start with '[' -- keep as-is but convert slashes
        return internalName.replace('/', '.');
    }
}

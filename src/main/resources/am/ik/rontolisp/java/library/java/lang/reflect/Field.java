// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// A field a class declares, found by its name or listed (Class.getDeclaredField,
// getField, getDeclaredFields, getFields): its type, its modifiers and the function reading
// and writing it, which the lowering made with the class objects. Two made for one field
// are equal. The order of a refusal's checks and its words are a JDK's answers.
public final class Field extends AccessibleObject implements Member {

    private final Class<?> declaring;

    private final String name;

    private final Class<?> type;

    // its modifiers with AccessibleObject's bits: whether its class can be named outside
    // its package, whether it is a class of the JDK's and why nothing of it may be in the
    // compiled program
    private final int flags;

    // where it is (of a class of the JDK, its module): who may read it is decided by them
    private final String packageName;

    private final String topLevel;

    // the function of a value, a value to write and a mode: 0 whether the value is one the
    // field can be used on, 1 the value read, 2 the field written
    private final Object reader;

    private Field(Class<?> declaring, String name, Class<?> type, int flags, String packageName, String topLevel,
            Object reader) {
        this.declaring = declaring;
        this.name = name;
        this.type = type;
        this.flags = flags;
        this.packageName = packageName;
        this.topLevel = topLevel;
        this.reader = reader;
    }

    // what Class.getDeclaredField finds
    static Field found(Class<?> declaring, String name, Class<?> type, int flags, String packageName,
            String topLevel, Object reader) {
        return new Field(declaring, name, type, flags, packageName, topLevel, reader);
    }

    // the fields of a class of the JDK the lowering writes as a text, an entry each: "name
    // flags type" (AccessibleObject.types); each read and written through the
    // lowering's functions of its use and its class's test
    static Object[] jdkAll(Class<?> declaring, String text, Object[] types, String module, int classFlags,
            int test) {
        String[] entries = text.isEmpty() ? new String[0] : text.split(";");
        Object[] made = new Object[entries.length];
        for (int index = 0; index < made.length; index++) {
            made[index] = jdk(declaring, entries[index].split(" "), types, module, classFlags, test);
        }
        return made;
    }

    // the field of a name a class of the JDK declares, from its line of the lowering's text
    // of the fields (AccessibleObject.described); null where it declares none of the name
    static Field jdkNamed(Class<?> declaring, String name, String text, Object[] types) {
        String line = described(text, types, declaring);
        if (line == null) {
            return unknown(declaring, name);
        }
        for (String entry : entries(line)) {
            String[] words = entry.split(" ");
            if (words[0].equals(name)) {
                return jdk(declaring, words, types, module(line), classFlags(line), test(line));
            }
        }
        return null;
    }

    private static Field jdk(Class<?> declaring, String[] words, Object[] types, String module, int classFlags,
            int test) {
        return new Field(declaring, words[0], type(types, words[2]), flags(words[1], classFlags), "", module,
                reads0(test, use(words[1])));
    }

    // a class whose members are not kept: one of a library the program uses, the
    // library's own that is no public class of the JDK, or one of the JDK the program holds
    // only as the type of a member reflection found
    static Field unknown(Class<?> declaring, String name) {
        throw new UnsupportedOperationException("getDeclaredField(\"" + name + "\") of " + declaring.getName()
                + ", whose members the program does not keep, is not supported by the experimental Java subset yet");
    }

    public String getName() {
        return name;
    }

    public Class<?> getDeclaringClass() {
        return declaring;
    }

    public Class<?> getType() {
        return type;
    }

    // its type as the declaration names it
    public Type getGenericType() {
        Object made = Types.generic0(declaring, name, 1, declaring);
        return made != null ? (Type) made : type;
    }

    // its type as the declaration names it, with the type annotations written in it
    public AnnotatedType getAnnotatedType() {
        return rontolisp.internal.AnnotatedTypes.of(getGenericType(),
                rontolisp.internal.AnnotatedTypes.annotated0(declaring, name, 1));
    }

    public String toGenericString() {
        String written = Modifier.toString(flags & Modifier.fieldModifiers());
        return (written.isEmpty() ? "" : written + " ") + getGenericType().getTypeName() + " "
                + declaring.getTypeName() + "." + name;
    }

    public Annotation[] getDeclaredAnnotations() {
        return annotations(annotations0(declaring, name, 0));
    }

    // as Modifier decodes them
    public int getModifiers() {
        return flags & 0xFFFF;
    }

    public boolean isEnumConstant() {
        return (flags & 0x4000) != 0;
    }

    public boolean isSynthetic() {
        return false;
    }

    // the value of an object's field, or of a static one, as the class reading may read
    // it: the lowering tells which class reads
    public native Object get(Object obj) throws IllegalAccessException;

    // get, called by a class of a package, of a top-level class and of a name and the
    // classes above it, which under tells an instance of
    static Object read(Field field, Object obj, String callerPackage, String callerTopLevel, String caller,
            Object under) throws IllegalAccessException {
        field.usable(obj, callerPackage, callerTopLevel, caller, under, "get");
        if ((field.flags & Modifier.STATIC) == 0 && !test0(field.reader, obj)) {
            throw new IllegalArgumentException("Can not get " + field.described() + " on " + obj.getClass().getName());
        }
        return use0(field.reader, obj, null);
    }

    // the field's value as a primitive type takes it: a typed read, after the checks of a
    // read
    static Object readAs(Field field, Object obj, Class<?> as, String callerPackage, String callerTopLevel,
            String caller, Object under) throws IllegalAccessException {
        field.usable(obj, callerPackage, callerTopLevel, caller, under, "get");
        if (!field.type.isPrimitive() || Executable.widened(as, zero(field.type)) == null) {
            throw new IllegalArgumentException("Attempt to get " + field.type.getName() + " field \""
                    + field.declaring.getName() + "." + field.name + "\" with illegal data type conversion to "
                    + as.getName());
        }
        if ((field.flags & Modifier.STATIC) == 0 && !test0(field.reader, obj)) {
            throw new IllegalArgumentException("Can not get " + field.described() + " on " + obj.getClass().getName());
        }
        return Executable.widened(as, use0(field.reader, obj, null));
    }

    public native void set(Object obj, Object value) throws IllegalAccessException;

    // set, called by a class of a package, of a top-level class and of a name and the
    // classes above it, which under tells an instance of
    static void written(Field field, Object obj, Object value, String callerPackage, String callerTopLevel,
            String caller, Object under) throws IllegalAccessException {
        field.usable(obj, callerPackage, callerTopLevel, caller, under, "set");
        boolean isStatic = (field.flags & Modifier.STATIC) != 0;
        if (!isStatic && !test0(field.reader, obj)) {
            throw new IllegalArgumentException("Can not set " + field.described() + " to " + obj.getClass().getName());
        }
        String given = value == null ? "null value" : value.getClass().getName();
        if (field.fixed()) {
            throw new IllegalAccessException("Can not set " + field.described() + " to " + given);
        }
        Object taken = value;
        if (field.type.isPrimitive()) {
            taken = value == null ? null : Executable.widened(field.type, value);
            if (taken == null) {
                throw new IllegalArgumentException("Can not set " + field.described() + " to " + given);
            }
        }
        else if (value != null && !field.type.isInstance(value)) {
            throw new IllegalArgumentException("Can not set " + field.described() + " to " + given);
        }
        write0(field.reader, obj, taken);
    }

    // a typed write: the value of a primitive type, boxed
    static void writtenAs(Field field, Object obj, Object value, String callerPackage, String callerTopLevel,
            String caller, Object under) throws IllegalAccessException {
        field.usable(obj, callerPackage, callerTopLevel, caller, under, "set");
        Object taken = field.type.isPrimitive() ? Executable.widened(field.type, value) : null;
        if (taken == null) {
            throw new IllegalArgumentException(
                    "Can not set " + field.described() + " to (" + primitive(value) + ")" + value);
        }
        if ((field.flags & Modifier.STATIC) == 0 && !test0(field.reader, obj)) {
            throw new IllegalArgumentException("Can not set " + field.described() + " on " + obj.getClass().getName());
        }
        if (field.fixed()) {
            throw new IllegalAccessException("Can not set " + field.described() + " to ("
                    + field.type.getName() + ")" + taken);
        }
        write0(field.reader, obj, taken);
    }

    // the checks every use makes first: an object for an instance's field, then who uses
    // it, then -- of a class of the JDK -- whether the compiled program holds the field
    private void usable(Object obj, String callerPackage, String callerTopLevel, String caller, Object under,
            String use) throws IllegalAccessException {
        if ((flags & Modifier.STATIC) == 0 && obj == null) {
            throw new NullPointerException();
        }
        if (!isAccessible()) {
            checkAccess(declaring, flags, packageName, topLevel, obj, callerPackage, callerTopLevel, caller, under);
        }
        if ((flags & (LACKING | ABSENT)) != 0) {
            throw new UnsupportedOperationException(missing(flags, use, declaring.getName() + "." + name));
        }
    }

    String closed() {
        return closed(flags, declaring, topLevel, "field " + toString());
    }

    // whether the field may not be written: final, where the program did not ask for its
    // use or it is static or a record's
    private boolean fixed() {
        return (flags & Modifier.FINAL) != 0
                && (!isAccessible() || (flags & Modifier.STATIC) != 0 || declaring.isRecord());
    }

    // the field as a refusal names it
    private String described() {
        String written = Modifier.toString(flags & (Modifier.STATIC | Modifier.FINAL));
        return (written.isEmpty() ? "" : written + " ") + type.getName() + " field " + declaring.getName() + "." + name;
    }

    // the primitive type a box holds a value of
    private static String primitive(Object box) {
        return box instanceof Boolean ? "boolean" : box instanceof Character ? "char" : box instanceof Byte ? "byte"
                : box instanceof Short ? "short" : box instanceof Integer ? "int" : box instanceof Long ? "long"
                        : box instanceof Float ? "float" : "double";
    }

    // the box of a primitive type's zero, which tells what the type widens to
    private static Object zero(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.FALSE;
        }
        if (primitive == char.class) {
            return Character.valueOf((char) 0);
        }
        if (primitive == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (primitive == short.class) {
            return Short.valueOf((short) 0);
        }
        if (primitive == int.class) {
            return Integer.valueOf(0);
        }
        if (primitive == long.class) {
            return Long.valueOf(0L);
        }
        if (primitive == float.class) {
            return Float.valueOf(0f);
        }
        return Double.valueOf(0d);
    }

    // the typed reads and writes, lowered where the program calls them as get and set are:
    // readAs and writtenAs told the class calling
    public native boolean getBoolean(Object obj) throws IllegalAccessException;

    public native byte getByte(Object obj) throws IllegalAccessException;

    public native char getChar(Object obj) throws IllegalAccessException;

    public native short getShort(Object obj) throws IllegalAccessException;

    public native int getInt(Object obj) throws IllegalAccessException;

    public native long getLong(Object obj) throws IllegalAccessException;

    public native float getFloat(Object obj) throws IllegalAccessException;

    public native double getDouble(Object obj) throws IllegalAccessException;

    public native void setBoolean(Object obj, boolean z) throws IllegalAccessException;

    public native void setByte(Object obj, byte b) throws IllegalAccessException;

    public native void setChar(Object obj, char c) throws IllegalAccessException;

    public native void setShort(Object obj, short s) throws IllegalAccessException;

    public native void setInt(Object obj, int i) throws IllegalAccessException;

    public native void setLong(Object obj, long l) throws IllegalAccessException;

    public native void setFloat(Object obj, float f) throws IllegalAccessException;

    public native void setDouble(Object obj, double d) throws IllegalAccessException;

    public boolean equals(Object other) {
        return other instanceof Field field && field.declaring == declaring && field.name.equals(name);
    }

    // the hash code of the declaring class's name and of the field's, exclusive-or'd
    public int hashCode() {
        return declaring.getName().hashCode() ^ name.hashCode();
    }

    public String toString() {
        String written = Modifier.toString(flags & Modifier.fieldModifiers());
        return (written.isEmpty() ? "" : written + " ") + type.getTypeName() + " " + declaring.getTypeName() + "."
                + name;
    }
}

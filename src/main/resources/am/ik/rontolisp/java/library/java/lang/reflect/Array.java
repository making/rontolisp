// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// Arrays made of a class object and used as objects. An array is a vector of the host's,
// whose element type tells an array of int, byte, char, float, double, boolean and Object,
// and the run time records the class of any other -- an array of long, of short, of other
// references -- where its type is widened (.kb/java-frontend.md, "The class of an array"):
// an array this class makes is recorded as the array of the class object asked, which the
// lowering's table of the class objects answers. A reference stored is not checked against
// the array's class, as the language's store is not.
public final class Array {

    private Array() {
    }

    public static Object newInstance(Class<?> componentType, int length) throws NegativeArraySizeException {
        if (componentType == null) {
            throw new NullPointerException();
        }
        if (length < 0) {
            throw new NegativeArraySizeException(String.valueOf(length));
        }
        if (isVoid(componentType) || dimensions(componentType) >= 255) {
            throw new IllegalArgumentException();
        }
        classes0(componentType, 1);
        return make0(record0(componentType), length);
    }

    public static Object newInstance(Class<?> componentType, int... dimensions)
            throws IllegalArgumentException, NegativeArraySizeException {
        if (componentType == null) {
            throw new NullPointerException();
        }
        int levels = dimensions.length;
        if (levels == 0 || levels > 255) {
            throw new IllegalArgumentException();
        }
        for (int length : dimensions) {
            if (length < 0) {
                throw new NegativeArraySizeException(String.valueOf(length));
            }
        }
        if (isVoid(componentType) || dimensions(componentType) + levels > 255) {
            throw new IllegalArgumentException();
        }
        classes0(componentType, levels);
        // what each level is an array of, the innermost of the class asked
        Object[] records = new Object[levels];
        Object record = record0(componentType);
        for (int level = levels - 1; level >= 0; level--) {
            records[level] = record;
            if (level > 0) {
                record = nest0(record);
            }
        }
        return rows(records, dimensions, 0);
    }

    private static Object rows(Object[] records, int[] dimensions, int level) {
        Object made = make0(records[level], dimensions[level]);
        if (level + 1 < dimensions.length) {
            for (int index = 0; index < dimensions[level]; index++) {
                set0(made, index, rows(records, dimensions, level + 1));
            }
        }
        return made;
    }

    private static boolean isVoid(Class<?> type) {
        return type.isPrimitive() && type.getName().equals("void");
    }

    // how many dimensions a type has: the '[' its name starts with
    private static int dimensions(Class<?> type) {
        String name = type.getName();
        int count = 0;
        while (count < name.length() && name.charAt(count) == '[') {
            count++;
        }
        return count;
    }

    public static int getLength(Object array) throws IllegalArgumentException {
        kind(array);
        return length0(array);
    }

    public static Object get(Object array, int index) throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        int kind = kind(array);
        within(array, index);
        switch (kind) {
            case 'Z':
                return getBoolean0(array, index);
            case 'B':
                return getByte0(array, index);
            case 'C':
                return getChar0(array, index);
            case 'S':
                return getShort0(array, index);
            case 'I':
                return getInt0(array, index);
            case 'J':
                return getLong0(array, index);
            case 'F':
                return getFloat0(array, index);
            case 'D':
                return getDouble0(array, index);
            default:
                return get0(array, index);
        }
    }

    public static boolean getBoolean(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        if (primitive(array, index) != 'Z') {
            throw mismatch();
        }
        return getBoolean0(array, index);
    }

    public static byte getByte(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        if (primitive(array, index) != 'B') {
            throw mismatch();
        }
        return getByte0(array, index);
    }

    public static char getChar(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        if (primitive(array, index) != 'C') {
            throw mismatch();
        }
        return getChar0(array, index);
    }

    public static short getShort(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        switch (primitive(array, index)) {
            case 'B':
                return getByte0(array, index);
            case 'S':
                return getShort0(array, index);
            default:
                throw mismatch();
        }
    }

    public static int getInt(Object array, int index) throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        switch (primitive(array, index)) {
            case 'B':
                return getByte0(array, index);
            case 'C':
                return getChar0(array, index);
            case 'S':
                return getShort0(array, index);
            case 'I':
                return getInt0(array, index);
            default:
                throw mismatch();
        }
    }

    public static long getLong(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        int kind = primitive(array, index);
        return kind == 'J' ? getLong0(array, index) : wider(array, index, kind);
    }

    public static float getFloat(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        int kind = primitive(array, index);
        if (kind == 'F') {
            return getFloat0(array, index);
        }
        return kind == 'J' ? (float) getLong0(array, index) : (float) wider(array, index, kind);
    }

    public static double getDouble(Object array, int index)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        int kind = primitive(array, index);
        if (kind == 'D') {
            return getDouble0(array, index);
        }
        if (kind == 'F') {
            return getFloat0(array, index);
        }
        return kind == 'J' ? (double) getLong0(array, index) : (double) wider(array, index, kind);
    }

    // an element an int holds, of an array of a kind getInt reads
    private static int wider(Object array, int index, int kind) {
        switch (kind) {
            case 'B':
                return getByte0(array, index);
            case 'C':
                return getChar0(array, index);
            case 'S':
                return getShort0(array, index);
            case 'I':
                return getInt0(array, index);
            default:
                throw mismatch();
        }
    }

    // a reference as it is; a box, unwrapped and widened, into an array of a primitive type
    // that takes its value
    public static void set(Object array, int index, Object value)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        int kind = kind(array);
        if (kind == 'L' || kind == '[') {
            within(array, index);
            set0(array, index, value);
            return;
        }
        if (value == null) {
            throw new IllegalArgumentException();
        }
        within(array, index);
        if (value instanceof Boolean truth) {
            if (kind != 'Z') {
                throw mismatch();
            }
            setBoolean0(array, index, truth.booleanValue());
        }
        else if (value instanceof Character letter) {
            store(array, index, kind, letter.charValue(), 'C');
        }
        else if (value instanceof Byte number) {
            store(array, index, kind, number.byteValue(), 'B');
        }
        else if (value instanceof Short number) {
            store(array, index, kind, number.shortValue(), 'S');
        }
        else if (value instanceof Integer number) {
            store(array, index, kind, number.intValue(), 'I');
        }
        else if (value instanceof Long number) {
            storeLong(array, index, kind, number.longValue());
        }
        else if (value instanceof Float number) {
            storeFloat(array, index, kind, number.floatValue());
        }
        else if (value instanceof Double number) {
            if (kind != 'D') {
                throw mismatch();
            }
            setDouble0(array, index, number.doubleValue());
        }
        else {
            throw mismatch();
        }
    }

    public static void setBoolean(Object array, int index, boolean z)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        if (primitive(array, index) != 'Z') {
            throw mismatch();
        }
        setBoolean0(array, index, z);
    }

    public static void setByte(Object array, int index, byte b)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        store(array, index, primitive(array, index), b, 'B');
    }

    public static void setChar(Object array, int index, char c)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        store(array, index, primitive(array, index), c, 'C');
    }

    public static void setShort(Object array, int index, short s)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        store(array, index, primitive(array, index), s, 'S');
    }

    public static void setInt(Object array, int index, int i)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        store(array, index, primitive(array, index), i, 'I');
    }

    public static void setLong(Object array, int index, long l)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        storeLong(array, index, primitive(array, index), l);
    }

    public static void setFloat(Object array, int index, float f)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        storeFloat(array, index, primitive(array, index), f);
    }

    public static void setDouble(Object array, int index, double d)
            throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
        if (primitive(array, index) != 'D') {
            throw mismatch();
        }
        setDouble0(array, index, d);
    }

    // a value of an integral type of the kind given (a char's is its code) stored in an array
    // whose type it widens to (JLS 5.1.2): a byte to any but char, a char or a short to int
    // and above, an int to long and the floating types
    private static void store(Object array, int index, int kind, int value, int of) {
        switch (kind) {
            case 'B':
                if (of != 'B') {
                    throw mismatch();
                }
                setByte0(array, index, (byte) value);
                return;
            case 'C':
                if (of != 'C') {
                    throw mismatch();
                }
                setChar0(array, index, (char) value);
                return;
            case 'S':
                if (of != 'B' && of != 'S') {
                    throw mismatch();
                }
                setShort0(array, index, (short) value);
                return;
            case 'I':
                setInt0(array, index, value);
                return;
            case 'J':
                setLong0(array, index, value);
                return;
            case 'F':
                setFloat0(array, index, value);
                return;
            case 'D':
                setDouble0(array, index, value);
                return;
            default:
                throw mismatch();
        }
    }

    private static void storeLong(Object array, int index, int kind, long value) {
        switch (kind) {
            case 'J':
                setLong0(array, index, value);
                return;
            case 'F':
                setFloat0(array, index, value);
                return;
            case 'D':
                setDouble0(array, index, value);
                return;
            default:
                throw mismatch();
        }
    }

    private static void storeFloat(Object array, int index, int kind, float value) {
        switch (kind) {
            case 'F':
                setFloat0(array, index, value);
                return;
            case 'D':
                setDouble0(array, index, value);
                return;
            default:
                throw mismatch();
        }
    }

    // the kind of an array: the letter its class's name spells after the '[', or refused
    private static int kind(Object array) {
        if (array == null) {
            throw new NullPointerException();
        }
        int kind = kind0(array);
        if (kind == 0) {
            throw new IllegalArgumentException("Argument is not an array");
        }
        return kind;
    }

    // the kind of an array of a primitive type an element of which is at an index
    private static int primitive(Object array, int index) {
        int kind = kind(array);
        if (kind == 'L' || kind == '[') {
            throw new IllegalArgumentException("Argument is not an array of primitive type");
        }
        within(array, index);
        return kind;
    }

    private static void within(Object array, int index) {
        if (index < 0 || index >= length0(array)) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private static IllegalArgumentException mismatch() {
        return new IllegalArgumentException("argument type mismatch");
    }

    // the letter of an array's elements as its class's name spells it ('I' an int, 'L' a
    // class, '[' an array ..), or 0 for an object that is no array
    private static native int kind0(Object array);

    private static native int length0(Object array);

    // what the run time records of an array of a class (its simple name and the names of the
    // array types it is an instance of), and of an array of such arrays
    private static native Object record0(Class<?> componentType);

    private static native Object nest0(Object record);

    // a new array of an element type's default, recorded as the class of the record
    private static native Object make0(Object record, int length);

    // the class objects of the arrays of a class, as deep as the levels, made where the
    // program can ask an array its class object
    private static native void classes0(Class<?> componentType, int levels);

    private static native boolean getBoolean0(Object array, int index);

    private static native byte getByte0(Object array, int index);

    private static native char getChar0(Object array, int index);

    private static native short getShort0(Object array, int index);

    private static native int getInt0(Object array, int index);

    private static native long getLong0(Object array, int index);

    private static native float getFloat0(Object array, int index);

    private static native double getDouble0(Object array, int index);

    private static native Object get0(Object array, int index);

    private static native void setBoolean0(Object array, int index, boolean value);

    private static native void setByte0(Object array, int index, byte value);

    private static native void setChar0(Object array, int index, char value);

    private static native void setShort0(Object array, int index, short value);

    private static native void setInt0(Object array, int index, int value);

    private static native void setLong0(Object array, int index, long value);

    private static native void setFloat0(Object array, int index, float value);

    private static native void setDouble0(Object array, int index, double value);

    private static native void set0(Object array, int index, Object value);
}

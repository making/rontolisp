// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.invoke;

import java.util.List;

// The type of a method handle, and of a call of one: what it returns and the types of its
// parameters, class objects compared by identity. Two of the same types are equal; each
// factory makes a new one, which the specification allows.
public final class MethodType implements java.io.Serializable {

    private final Class<?> rtype;

    private final Class<?>[] ptypes;

    private MethodType(Class<?> rtype, Class<?>[] ptypes) {
        this.rtype = rtype;
        this.ptypes = ptypes;
    }

    // a type of a copy of the parameter types, each checked: no null, no void, and no more
    // than 255 slots, a long and a double taking two
    private static MethodType made(Class<?> rtype, Class<?>[] ptypes) {
        if (rtype == null) {
            throw new NullPointerException();
        }
        int slots = 0;
        for (Class<?> ptype : ptypes) {
            if (ptype == null) {
                throw new NullPointerException();
            }
            if (ptype == void.class) {
                throw new IllegalArgumentException("parameter type cannot be void");
            }
            slots += ptype == long.class || ptype == double.class ? 2 : 1;
        }
        if (slots > 255) {
            throw new IllegalArgumentException("bad parameter count " + slots);
        }
        return new MethodType(rtype, ptypes);
    }

    private static Class<?>[] copy(Class<?>[] types, int length) {
        Class<?>[] copied = new Class<?>[length];
        for (int index = 0; index < length && index < types.length; index++) {
            copied[index] = types[index];
        }
        return copied;
    }

    // the type of a call of a method handle: its arguments' types, which no class names
    // void, and what it returns (MethodHandle.exact)
    static MethodType site(Class<?> rtype, Class<?>[] ptypes) {
        return new MethodType(rtype, ptypes);
    }

    public static MethodType methodType(Class<?> rtype, Class<?>[] ptypes) {
        return made(rtype, copy(ptypes, ptypes.length));
    }

    public static MethodType methodType(Class<?> rtype, List<Class<?>> ptypes) {
        Class<?>[] taken = new Class<?>[ptypes.size()];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = ptypes.get(index);
        }
        return made(rtype, taken);
    }

    public static MethodType methodType(Class<?> rtype, Class<?> ptype0, Class<?>... ptypes) {
        Class<?>[] taken = new Class<?>[ptypes.length + 1];
        taken[0] = ptype0;
        for (int index = 0; index < ptypes.length; index++) {
            taken[index + 1] = ptypes[index];
        }
        return made(rtype, taken);
    }

    public static MethodType methodType(Class<?> rtype) {
        return made(rtype, new Class<?>[0]);
    }

    public static MethodType methodType(Class<?> rtype, Class<?> ptype0) {
        return made(rtype, new Class<?>[] { ptype0 });
    }

    public static MethodType methodType(Class<?> rtype, MethodType ptypes) {
        return made(rtype, ptypes.ptypes);
    }

    // Object for each argument, an Object[] after them where finalArray, Object returned
    public static MethodType genericMethodType(int objectArgCount, boolean finalArray) {
        if (objectArgCount < 0 || objectArgCount > 255 - (finalArray ? 1 : 0)) {
            throw new IllegalArgumentException("bad parameter count " + objectArgCount);
        }
        Class<?>[] taken = new Class<?>[objectArgCount + (finalArray ? 1 : 0)];
        for (int index = 0; index < objectArgCount; index++) {
            taken[index] = Object.class;
        }
        if (finalArray) {
            taken[objectArgCount] = Object[].class;
        }
        return new MethodType(Object.class, taken);
    }

    public static MethodType genericMethodType(int objectArgCount) {
        return genericMethodType(objectArgCount, false);
    }

    public MethodType changeParameterType(int num, Class<?> nptype) {
        Class<?>[] taken = copy(ptypes, ptypes.length);
        taken[num] = nptype;
        return made(rtype, taken);
    }

    public MethodType insertParameterTypes(int num, Class<?>... ptypesToInsert) {
        if (num < 0 || num > ptypes.length) {
            throw new IndexOutOfBoundsException("bad index: " + num);
        }
        Class<?>[] taken = new Class<?>[ptypes.length + ptypesToInsert.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = index < num ? ptypes[index]
                    : index < num + ptypesToInsert.length ? ptypesToInsert[index - num]
                            : ptypes[index - ptypesToInsert.length];
        }
        return made(rtype, taken);
    }

    public MethodType insertParameterTypes(int num, List<Class<?>> ptypesToInsert) {
        return insertParameterTypes(num, ptypesToInsert.toArray(new Class<?>[0]));
    }

    public MethodType appendParameterTypes(Class<?>... ptypesToInsert) {
        return insertParameterTypes(ptypes.length, ptypesToInsert);
    }

    public MethodType appendParameterTypes(List<Class<?>> ptypesToInsert) {
        return insertParameterTypes(ptypes.length, ptypesToInsert);
    }

    public MethodType dropParameterTypes(int start, int end) {
        if (start < 0 || start > end || end > ptypes.length) {
            throw new IndexOutOfBoundsException("start=" + start + " end=" + end);
        }
        Class<?>[] taken = new Class<?>[ptypes.length - (end - start)];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = ptypes[index < start ? index : index + end - start];
        }
        return new MethodType(rtype, taken);
    }

    public MethodType changeReturnType(Class<?> nrtype) {
        return made(nrtype, ptypes);
    }

    // whether a type returned or taken is primitive: void counts
    public boolean hasPrimitives() {
        if (rtype.isPrimitive()) {
            return true;
        }
        for (Class<?> ptype : ptypes) {
            if (ptype.isPrimitive()) {
                return true;
            }
        }
        return false;
    }

    // whether a type returned or taken is a primitive type's box, or Void
    public boolean hasWrappers() {
        if (unwrapped(rtype) != rtype) {
            return true;
        }
        for (Class<?> ptype : ptypes) {
            if (unwrapped(ptype) != ptype) {
                return true;
            }
        }
        return false;
    }

    // Object for each reference type
    public MethodType erase() {
        Class<?>[] taken = new Class<?>[ptypes.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = ptypes[index].isPrimitive() ? ptypes[index] : Object.class;
        }
        return new MethodType(rtype.isPrimitive() ? rtype : Object.class, taken);
    }

    // Object for every type
    public MethodType generic() {
        return genericMethodType(ptypes.length);
    }

    // a box for each primitive type, Void for void
    public MethodType wrap() {
        Class<?>[] taken = new Class<?>[ptypes.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = wrapped(ptypes[index]);
        }
        return new MethodType(wrapped(rtype), taken);
    }

    // the primitive type for each box, void for Void
    public MethodType unwrap() {
        Class<?>[] taken = new Class<?>[ptypes.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = unwrapped(ptypes[index]);
        }
        return new MethodType(unwrapped(rtype), taken);
    }

    // a primitive type's box, Void void's; any other as it is
    static Class<?> wrapped(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        switch (type.getName()) {
            case "boolean":
                return Boolean.class;
            case "byte":
                return Byte.class;
            case "char":
                return Character.class;
            case "short":
                return Short.class;
            case "int":
                return Integer.class;
            case "long":
                return Long.class;
            case "float":
                return Float.class;
            case "double":
                return Double.class;
            default:
                return Void.class;
        }
    }

    // the primitive type of a box, void of Void; any other as it is
    static Class<?> unwrapped(Class<?> type) {
        if (type == Boolean.class) {
            return boolean.class;
        }
        if (type == Byte.class) {
            return byte.class;
        }
        if (type == Character.class) {
            return char.class;
        }
        if (type == Short.class) {
            return short.class;
        }
        if (type == Integer.class) {
            return int.class;
        }
        if (type == Long.class) {
            return long.class;
        }
        if (type == Float.class) {
            return float.class;
        }
        if (type == Double.class) {
            return double.class;
        }
        return type == Void.class ? void.class : type;
    }

    public Class<?> parameterType(int num) {
        return ptypes[num];
    }

    public int parameterCount() {
        return ptypes.length;
    }

    public Class<?> returnType() {
        return rtype;
    }

    public List<Class<?>> parameterList() {
        return List.of(ptypes);
    }

    public Class<?> lastParameterType() {
        return ptypes.length == 0 ? void.class : ptypes[ptypes.length - 1];
    }

    public Class<?>[] parameterArray() {
        return copy(ptypes, ptypes.length);
    }

    public boolean equals(Object x) {
        if (!(x instanceof MethodType other) || other.rtype != rtype || other.ptypes.length != ptypes.length) {
            return false;
        }
        for (int index = 0; index < ptypes.length; index++) {
            if (other.ptypes[index] != ptypes[index]) {
                return false;
            }
        }
        return true;
    }

    // the hash code of a list of the return type and the parameter types, as specified
    public int hashCode() {
        int hash = 31 + rtype.hashCode();
        for (Class<?> ptype : ptypes) {
            hash = 31 * hash + ptype.hashCode();
        }
        return hash;
    }

    // the simple names of the parameter types in parentheses, then the return type's
    public String toString() {
        StringBuilder written = new StringBuilder("(");
        for (int index = 0; index < ptypes.length; index++) {
            if (index > 0) {
                written.append(',');
            }
            written.append(ptypes[index].getSimpleName());
        }
        return written.append(')').append(rtype.getSimpleName()).toString();
    }

    // as a class file writes a method's descriptor
    public String toMethodDescriptorString() {
        StringBuilder written = new StringBuilder("(");
        for (Class<?> ptype : ptypes) {
            written.append(descriptor(ptype));
        }
        return written.append(')').append(descriptor(rtype)).toString();
    }

    public String descriptorString() {
        return toMethodDescriptorString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isPrimitive()) {
            switch (type.getName()) {
                case "boolean":
                    return "Z";
                case "byte":
                    return "B";
                case "char":
                    return "C";
                case "short":
                    return "S";
                case "int":
                    return "I";
                case "long":
                    return "J";
                case "float":
                    return "F";
                case "double":
                    return "D";
                default:
                    return "V";
            }
        }
        String name = type.getName().replace('.', '/');
        return type.isArray() ? name : "L" + name + ";";
    }
}

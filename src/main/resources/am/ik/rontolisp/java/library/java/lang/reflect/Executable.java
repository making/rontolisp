// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// A method or a constructor: what its parameters take and how a call of it hands them the
// arguments a program gives as objects.
public abstract sealed class Executable extends AccessibleObject
        implements Member, GenericDeclaration permits Constructor, Method {

    Executable() {
    }

    // what the lowering knows the executable by among its class's members: a method its
    // place, a constructor -1 less its place
    abstract Object member();

    public abstract TypeVariable<?>[] getTypeParameters();

    public abstract String toGenericString();

    // the types of the parameters as the declaration names them: of a constructor whose
    // declaration a compiler keeps no generic signature of, the types it erases to, an inner
    // class's instance around it and an enum's name and ordinal included
    public Type[] getGenericParameterTypes() {
        return Types.generic(getDeclaringClass(), member(), 2, this, getParameterTypes());
    }

    public Type[] getGenericExceptionTypes() {
        return Types.generic(getDeclaringClass(), member(), 3, this, getExceptionTypes());
    }

    public Annotation[] getDeclaredAnnotations() {
        return annotations(annotations0(getDeclaringClass(), member(), 0));
    }

    // the annotations of each parameter, as getParameterTypes() lists them: none of an inner
    // class's instance around it and of an enum's name and ordinal
    public Annotation[][] getParameterAnnotations() {
        int count = getParameterCount();
        Object[] made = (Object[]) annotations0(getDeclaringClass(), member(), 2);
        int first = made == null ? count : count - made.length;
        Annotation[][] taken = new Annotation[count][];
        for (int index = 0; index < count; index++) {
            taken[index] = index < first ? new Annotation[0] : annotations(made[index - first]);
        }
        return taken;
    }

    // the parameters, as getParameterTypes() lists them: their names and modifiers where a
    // compiler keeps them
    public Parameter[] getParameters() {
        int count = getParameterCount();
        Object[] kept = (Object[]) parameters0(getDeclaringClass(), member());
        Parameter[] made = new Parameter[count];
        for (int index = 0; index < count; index++) {
            made[index] = kept == null ? new Parameter(this, index, null, 0)
                    : new Parameter(this, index, (String) kept[2 * index], ((Integer) kept[2 * index + 1]).intValue());
        }
        return made;
    }

    // what a compiler keeps of the parameters: a name (null where it keeps none) and the
    // modifiers of each, or null where it keeps nothing
    static native Object parameters0(Class<?> declaring, Object member);

    public abstract Class<?> getDeclaringClass();

    public abstract String getName();

    public abstract int getModifiers();

    public abstract Class<?>[] getParameterTypes();

    public abstract int getParameterCount();

    public abstract Class<?>[] getExceptionTypes();

    // a method or a constructor of a variable arity has the bit Modifier calls TRANSIENT
    public boolean isVarArgs() {
        return (getModifiers() & Modifier.TRANSIENT) != 0;
    }

    public boolean isSynthetic() {
        return false;
    }

    // converted, where a call of the program gives a member found by reflection arguments:
    // the lowering tells, and a program giving none holds no conversion
    static native Object[] converted0(Class<?>[] types, Object[] args);

    // the arguments given as the parameters take them: of a primitive type the value of a box
    // the type takes by an identity or a widening conversion, as that type's box; of a
    // reference type an instance or null
    static Object[] converted(Class<?>[] types, Object[] args) {
        Object[] taken = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            Object given = args[index];
            if (!type.isPrimitive()) {
                if (given != null && !type.isInstance(given)) {
                    throw new IllegalArgumentException("argument type mismatch");
                }
                taken[index] = given;
            }
            else if (given == null) {
                throw new IllegalArgumentException(new NullPointerException());
            }
            else {
                Object widened = widened(type, given);
                if (widened == null) {
                    throw new IllegalArgumentException("argument type mismatch");
                }
                taken[index] = widened;
            }
        }
        return taken;
    }

    // a box's value as a primitive type takes it, boxed as that type, or null where the type
    // takes it by neither an identity nor a widening conversion (JLS 5.1.2)
    static Object widened(Class<?> type, Object value) {
        if (type == boolean.class) {
            return value instanceof Boolean ? value : null;
        }
        if (type == char.class) {
            return value instanceof Character ? value : null;
        }
        if (type == byte.class) {
            return value instanceof Byte ? value : null;
        }
        if (type == short.class) {
            return value instanceof Short ? value : value instanceof Byte b ? Short.valueOf(b.byteValue()) : null;
        }
        long whole;
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            whole = ((Number) value).intValue();
        }
        else if (value instanceof Character c) {
            whole = c.charValue();
        }
        else if (value instanceof Long l) {
            if (type == int.class) {
                return null;
            }
            whole = l.longValue();
        }
        else if (value instanceof Float f) {
            return type == float.class ? f : type == double.class ? Double.valueOf(f.floatValue()) : null;
        }
        else if (value instanceof Double d) {
            return type == double.class ? d : null;
        }
        else {
            return null;
        }
        if (type == int.class) {
            return Integer.valueOf((int) whole);
        }
        if (type == long.class) {
            return Long.valueOf(whole);
        }
        if (type == float.class) {
            return Float.valueOf((float) whole);
        }
        return Double.valueOf((double) whole);
    }

    // the names of types as a member's text writes them, a comma between two
    static String names(Class<?>[] types) {
        StringBuilder written = new StringBuilder();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) {
                written.append(',');
            }
            written.append(types[index].getTypeName());
        }
        return written.toString();
    }

    // the text of a method or a constructor: its modifiers ('default' after its access for an
    // interface's default method), what a method returns, its class and name, the types of
    // its parameters and those it throws
    static String text(int modifiers, boolean isDefault, Class<?> returnType, Class<?> declaring, String name,
            Class<?>[] parameterTypes, Class<?>[] exceptionTypes) {
        StringBuilder written = new StringBuilder();
        int access = modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
        int rest = modifiers & ~access;
        if (access != 0) {
            written.append(Modifier.toString(access)).append(' ');
        }
        if (isDefault) {
            written.append("default ");
        }
        if (rest != 0) {
            written.append(Modifier.toString(rest)).append(' ');
        }
        if (returnType != null) {
            written.append(returnType.getTypeName()).append(' ').append(declaring.getTypeName()).append('.');
        }
        written.append(name).append('(').append(names(parameterTypes)).append(')');
        if (exceptionTypes.length > 0) {
            written.append(" throws ").append(names(exceptionTypes));
        }
        return written.toString();
    }
}

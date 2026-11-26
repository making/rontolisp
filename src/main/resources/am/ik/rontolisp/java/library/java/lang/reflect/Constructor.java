// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A constructor a class declares: the types it takes (an inner class's instance around it
// first, an enum's name and ordinal ahead of the declared ones, as a JDK's reflection
// lists them) and throws, its modifiers, what decides who may call it, and the function
// making an instance of the arguments its parameters take, which the lowering made with
// the class objects. Two made for one constructor are equal.
public final class Constructor<T> extends Executable {

    private final Class<T> declaring;

    // its place among the constructors its class declares
    private final int index;

    private final Class<?>[] parameterTypes;

    private final Class<?>[] exceptionTypes;

    // its modifiers with AccessibleObject's bits, and whether its class is abstract (no
    // instance is made), an enum (none made here) or an inner class, whose instance around
    // it is the first argument
    private final int flags;

    static final int ABSTRACT = 1 << 22;

    static final int ENUM = 1 << 23;

    static final int INNER = 1 << 24;

    // its package and the top-level class its declaration stands in (of a class of the
    // JDK, its module)
    private final String packageName;

    private final String topLevel;

    // the function of the arguments making an instance (of none where it takes none), or
    // null where none is made: an abstract class's, an enum's, a local class's, or one
    // taking arguments where the program gives none
    private final Object maker;

    private Constructor(Class<T> declaring, int index, Class<?>[] parameterTypes, Class<?>[] exceptionTypes,
            int flags, String packageName, String topLevel, Object maker) {
        this.declaring = declaring;
        this.index = index;
        // none: the lowering writes null
        this.parameterTypes = parameterTypes == null ? new Class<?>[0] : parameterTypes;
        this.exceptionTypes = exceptionTypes == null ? new Class<?>[0] : exceptionTypes;
        this.flags = flags;
        this.packageName = packageName;
        this.topLevel = topLevel;
        this.maker = maker;
    }

    // what a class object answers of a constructor
    static <T> Constructor<T> found(Class<T> declaring, int index, Class<?>[] parameterTypes,
            Class<?>[] exceptionTypes, int flags, String packageName, String topLevel, Object maker) {
        return new Constructor<>(declaring, index, parameterTypes, exceptionTypes, flags, packageName, topLevel,
                maker);
    }

    // a class whose members are not kept: one of a library the program uses, the
    // library's own that is no public class of the JDK, or one of the JDK the program holds
    // only as the type of a member reflection found
    static Object unknown(Class<?> declaring) {
        throw new UnsupportedOperationException("the constructors of " + declaring.getName()
                + ", whose members the program does not keep, are not supported by the experimental Java subset yet");
    }

    public Class<T> getDeclaringClass() {
        return declaring;
    }

    Object member() {
        return -1 - index;
    }

    public TypeVariable<Constructor<T>>[] getTypeParameters() {
        return Types.variables(Types.generic0(declaring, -1 - index, 0, this));
    }

    public String toGenericString() {
        return Types.text(flags & Modifier.constructorModifiers(), false, getTypeParameters(), null, declaring,
                declaring.getTypeName(), getGenericParameterTypes(), getGenericExceptionTypes(), isVarArgs());
    }

    public String getName() {
        return declaring.getName();
    }

    // as Modifier decodes them, with the bit of a variable arity
    public int getModifiers() {
        return flags & 0xFFFF;
    }

    public Class<?>[] getParameterTypes() {
        return Method.copy(parameterTypes);
    }

    public int getParameterCount() {
        return parameterTypes.length;
    }

    public Class<?>[] getExceptionTypes() {
        return Method.copy(exceptionTypes);
    }

    // a new instance, where the class calling may reach the constructor: the lowering
    // tells which class calls
    public native T newInstance(Object... initargs)
            throws InstantiationException, IllegalAccessException, InvocationTargetException;

    // newInstance, called by a class of a package, of a top-level class and of a name
    static Object made(Constructor<?> constructor, Object[] initargs, String callerPackage, String callerTopLevel,
            String caller) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        if (!constructor.isAccessible()) {
            // a protected constructor of another package makes an instance of its class,
            // which is under no class but its own: no under
            checkAccess(constructor.declaring, constructor.flags, constructor.packageName, constructor.topLevel, null,
                    callerPackage, callerTopLevel, caller, null);
        }
        if ((constructor.flags & ENUM) != 0) {
            throw new IllegalArgumentException("Cannot reflectively create enum objects");
        }
        if ((constructor.flags & ABSTRACT) != 0) {
            throw new InstantiationException();
        }
        int given = initargs == null ? 0 : initargs.length;
        int count = constructor.parameterTypes.length;
        if (given != count) {
            throw new IllegalArgumentException("wrong number of arguments: " + given + " expected: " + count);
        }
        Object[] taken = given == 0 ? initargs : converted0(constructor.parameterTypes, initargs);
        if ((constructor.flags & INNER) != 0 && taken[0] == null) {
            throw new InvocationTargetException(new NullPointerException());
        }
        if ((constructor.flags & (LACKING | ABSENT)) != 0) {
            throw new UnsupportedOperationException(missing(constructor.flags, "newInstance",
                    constructor.declaring.getName() + "(" + names(constructor.parameterTypes) + ")"));
        }
        if (constructor.maker == null) {
            throw new UnsupportedOperationException("newInstance of the constructor of a local class ("
                    + constructor.declaring.getName() + ") is not supported by the experimental Java subset yet");
        }
        try {
            return given == 0 ? make0(constructor.maker) : make1(constructor.maker, taken);
        }
        catch (Throwable thrown) {
            throw new InvocationTargetException(thrown);
        }
    }

    // whether a lookup of a class may use the constructor (AccessibleObject.reach); a
    // protected one of another package never, as its object is of its own class
    static int lookedUp(Constructor<?> constructor, String callerPackage, String callerTopLevel, String caller) {
        int reached = reach(constructor.declaring, constructor.flags, constructor.packageName, constructor.topLevel,
                callerPackage, callerTopLevel, caller);
        return reached == 2 ? 0 : reached;
    }

    // the call of a method handle on the constructor (java.lang.invoke): who may call it was
    // decided where it was found and the arguments are the ones its parameters take; what it
    // throws is thrown as it is
    static Object handled(Constructor<?> constructor, Object[] args) throws Throwable {
        if ((constructor.flags & ENUM) != 0) {
            throw new IllegalArgumentException("Cannot reflectively create enum objects");
        }
        if ((constructor.flags & ABSTRACT) != 0) {
            throw new InstantiationException(constructor.declaring.getName());
        }
        if ((constructor.flags & INNER) != 0 && args[0] == null) {
            throw new NullPointerException();
        }
        if ((constructor.flags & (LACKING | ABSENT)) != 0) {
            throw new UnsupportedOperationException(missing(constructor.flags, "newInstance",
                    constructor.declaring.getName() + "(" + names(constructor.parameterTypes) + ")"));
        }
        if (constructor.maker == null) {
            throw new UnsupportedOperationException("newInstance of the constructor of a local class ("
                    + constructor.declaring.getName() + ") is not supported by the experimental Java subset yet");
        }
        return args.length == 0 ? make0(constructor.maker) : make1(constructor.maker, args);
    }

    // Class.newInstance(): the constructor of no parameter a class object answers, made as
    // the class calling may; what the constructor throws is thrown as it is
    static Object instance(Class<?> type, Object constructor, String callerPackage, String callerTopLevel,
            String caller) throws InstantiationException, IllegalAccessException {
        if (type.isInterface() || type.isArray() || type.isPrimitive() || constructor == null) {
            throw new InstantiationException(type.getName());
        }
        try {
            return made((Constructor<?>) constructor, null, callerPackage, callerTopLevel, caller);
        }
        catch (InvocationTargetException ex) {
            raise0(ex.getCause());
            return null;
        }
    }

    String closed() {
        return closed(flags, declaring, topLevel, toString());
    }

    public boolean equals(Object other) {
        return other instanceof Constructor<?> constructor && constructor.declaring == declaring
                && constructor.index == index;
    }

    public int hashCode() {
        return declaring.getName().hashCode();
    }

    public String toString() {
        return text(flags & Modifier.constructorModifiers(), false, null, declaring, declaring.getTypeName(),
                parameterTypes, exceptionTypes);
    }

    // the function of none making an instance, and of the arguments
    private static native Object make0(Object maker);

    private static native Object make1(Object maker, Object[] args);

    // throws what a constructor threw, checked or not
    private static native void raise0(Throwable thrown);
}

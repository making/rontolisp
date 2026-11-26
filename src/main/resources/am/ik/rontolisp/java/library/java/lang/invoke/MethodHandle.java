// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.invoke;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

// A handle on a method or a constructor a lookup found, of a type: as found, adapted to
// another type, with its first argument bound, or of a variable arity. A call of
// invokeExact or invoke is the front end's (JLS 15.12.3: its type is its arguments' and what
// a cast or a statement makes of its result), lowered to exact or adapted with that type
// and the arguments boxed as their types box them.
public abstract class MethodHandle {

    private final MethodType type;

    MethodHandle(MethodType type) {
        this.type = type;
    }

    public MethodType type() {
        return type;
    }

    // the call the handle stands for, of arguments as its type takes them -- a primitive
    // type's as its box --, answering what its type returns, boxed, or null
    abstract Object call(Object[] args) throws Throwable;

    public final native Object invokeExact(Object... args) throws Throwable;

    public final native Object invoke(Object... args) throws Throwable;

    // a call of invokeExact: of a handle of the call's own type, else refused
    static Object exact(MethodHandle handle, MethodType site, Object[] args) throws Throwable {
        if (!handle.type.equals(site)) {
            throw new WrongMethodTypeException("handle's method type " + handle.type + " but found " + site);
        }
        return handle.call(args);
    }

    // a call of invoke: of the handle asType makes of the call's type
    static Object adapted(MethodHandle handle, MethodType site, Object[] args) throws Throwable {
        return handle.asType(site).call(args);
    }

    // a call as of a site whose arguments and result are each an Object
    public Object invokeWithArguments(Object... arguments) throws Throwable {
        Object[] given = arguments == null ? new Object[0] : arguments;
        return asType(MethodType.genericMethodType(given.length)).call(given);
    }

    public Object invokeWithArguments(List<?> arguments) throws Throwable {
        return invokeWithArguments(arguments.toArray());
    }

    // a handle of another type calling this one, each argument and the result converted
    public MethodHandle asType(MethodType newType) {
        if (newType.equals(type)) {
            return this;
        }
        if (!convertible(newType, type)) {
            throw new WrongMethodTypeException("cannot convert " + this + " to " + newType);
        }
        return new Adapted(newType, this);
    }

    // a handle of the parameters after the first, which takes the value given: a reference
    public MethodHandle bindTo(Object x) {
        if (type.parameterCount() == 0 || type.parameterType(0).isPrimitive()) {
            throw new IllegalArgumentException("no leading reference parameter");
        }
        MethodHandle fixed = asFixedArity();
        return new Bound(type.dropParameterTypes(0, 1), fixed, converted(x, Object.class, type.parameterType(0)));
    }

    public boolean isVarargsCollector() {
        return false;
    }

    public MethodHandle asFixedArity() {
        return this;
    }

    public String toString() {
        return "MethodHandle" + type;
    }

    // a handle calling a method: of an object, its first argument, where the method is not
    // static
    static MethodHandle method(MethodType type, Method method, boolean instance, String described,
            boolean varargs) {
        MethodHandle direct = new Direct(type, method, null, instance, described);
        return varargs ? new Collector(direct) : direct;
    }

    // a handle making an instance by a constructor
    static MethodHandle constructor(MethodType type, Constructor<?> constructor, boolean varargs) {
        MethodHandle direct = new Direct(type, null, constructor, false, null);
        return varargs ? new Collector(direct) : direct;
    }

    // whether a call of a type can call a handle of another: the same count, each argument
    // and the result converting as asType converts them
    static boolean convertible(MethodType from, MethodType to) {
        if (from.parameterCount() != to.parameterCount()) {
            return false;
        }
        for (int index = 0; index < from.parameterCount(); index++) {
            if (!converts(from.parameterType(index), to.parameterType(index))) {
                return false;
            }
        }
        return converts(to.returnType(), from.returnType());
    }

    // whether a value of a type converts to another: a reference to a reference by a cast, a
    // primitive to a primitive by a widening (JLS 5.1.2), a primitive to its box or a type
    // above that, a reference to a primitive by unboxing where it is a box, or above a box,
    // of a primitive type widening to it; a result of void to anything, anything to void. A
    // box is asked of a value of it, so that no box's class object is held for reflection
    static boolean converts(Class<?> from, Class<?> to) {
        if (from == to || from == void.class || to == void.class) {
            return true;
        }
        if (from.isPrimitive()) {
            return to.isPrimitive() ? widens(from, to) : to.isInstance(zero(from));
        }
        if (!to.isPrimitive()) {
            return true;
        }
        Class<?> unboxed = unboxed(from.getName());
        if (unboxed != null) {
            return widens(unboxed, to);
        }
        for (Class<?> primitive : new Class<?>[] { boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class }) {
            if (widens(primitive, to) && from.isInstance(zero(primitive))) {
                return true;
            }
        }
        return false;
    }

    // whether a primitive type is another or widens to it (JLS 5.1.2)
    static boolean widens(Class<?> from, Class<?> to) {
        if (from == to) {
            return true;
        }
        if (from == boolean.class || to == boolean.class || to == char.class || to == byte.class) {
            return false;
        }
        if (from == byte.class) {
            return true;
        }
        if (to == short.class) {
            return false;
        }
        if (from == short.class || from == char.class) {
            return true;
        }
        if (from == int.class) {
            return to == long.class || to == float.class || to == double.class;
        }
        return from == long.class ? to == float.class || to == double.class : from == float.class && to == double.class;
    }

    // a value converted as asType converts it: a reference cast, a box of a type unboxed and
    // widened to another's box -- one that is no box refused as no Number, one that does not
    // widen as not the box --, a result of void a null or a zero
    static Object converted(Object value, Class<?> from, Class<?> to) {
        if (from == to) {
            return value;
        }
        if (to == void.class) {
            return null;
        }
        if (from == void.class) {
            return to.isPrimitive() ? zero(to) : null;
        }
        if (!to.isPrimitive()) {
            if (!from.isPrimitive() && value != null && !to.isInstance(value)) {
                throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + to.getName());
            }
            return value;
        }
        if (value == null) {
            throw new NullPointerException();
        }
        Class<?> primitive = unboxed(value.getClass().getName());
        if (primitive == null) {
            throw new ClassCastException(
                    "class " + value.getClass().getName() + " cannot be cast to class java.lang.Number");
        }
        if (!widens(primitive, to)) {
            throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + box(to));
        }
        return widened(value, primitive, to);
    }

    // the primitive type of a box's class by its name, or null for one that is no box
    private static Class<?> unboxed(String name) {
        switch (name) {
            case "java.lang.Boolean":
                return boolean.class;
            case "java.lang.Byte":
                return byte.class;
            case "java.lang.Character":
                return char.class;
            case "java.lang.Short":
                return short.class;
            case "java.lang.Integer":
                return int.class;
            case "java.lang.Long":
                return long.class;
            case "java.lang.Float":
                return float.class;
            case "java.lang.Double":
                return double.class;
            default:
                return null;
        }
    }

    // the name of a primitive type's box
    private static String box(Class<?> primitive) {
        String name = primitive.getName();
        return name.equals("int") ? "java.lang.Integer"
                : name.equals("char") ? "java.lang.Character"
                        : "java.lang." + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // a primitive type's zero, boxed
    private static Object zero(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.FALSE;
        }
        return primitive == char.class ? (Object) Character.valueOf((char) 0) : widened(0, int.class, primitive);
    }

    // the box of a primitive type's value as the box of a type it widens to
    private static Object widened(Object value, Class<?> from, Class<?> to) {
        if (from == to) {
            return value;
        }
        Number number = value instanceof Character letter ? Integer.valueOf(letter.charValue()) : (Number) value;
        if (to == byte.class) {
            return Byte.valueOf(number.byteValue());
        }
        if (to == short.class) {
            return Short.valueOf(number.shortValue());
        }
        if (to == int.class) {
            return Integer.valueOf(number.intValue());
        }
        if (to == long.class) {
            return Long.valueOf(number.longValue());
        }
        return to == float.class ? (Object) Float.valueOf(number.floatValue()) : Double.valueOf(number.doubleValue());
    }

    // a method or a constructor a lookup found
    private static final class Direct extends MethodHandle {

        private final Method method;

        private final Constructor<?> constructor;

        // whether the first argument is the object the method is called on
        private final boolean instance;

        // the member as a refusal of a call on null names it
        private final String described;

        Direct(MethodType type, Method method, Constructor<?> constructor, boolean instance, String described) {
            super(type);
            this.method = method;
            this.constructor = constructor;
            this.instance = instance;
            this.described = described;
        }

        Object call(Object[] args) throws Throwable {
            if (constructor != null) {
                return made0(constructor, args);
            }
            if (!instance) {
                return called0(method, null, args);
            }
            Object receiver = args[0];
            if (receiver == null) {
                throw new NullPointerException("Cannot invoke " + described + " with null receiver");
            }
            Object[] rest = new Object[args.length - 1];
            for (int index = 0; index < rest.length; index++) {
                rest[index] = args[index + 1];
            }
            return called0(method, receiver, rest);
        }
    }

    // a handle called through another's type: each argument converted to the type the
    // other takes, the result back
    private static final class Adapted extends MethodHandle {

        private final MethodHandle target;

        Adapted(MethodType type, MethodHandle target) {
            super(type);
            this.target = target;
        }

        Object call(Object[] args) throws Throwable {
            MethodType from = type();
            MethodType to = target.type();
            Object[] taken = new Object[args.length];
            for (int index = 0; index < taken.length; index++) {
                taken[index] = converted(args[index], from.parameterType(index), to.parameterType(index));
            }
            return converted(target.call(taken), to.returnType(), from.returnType());
        }
    }

    // a handle with its first argument given
    private static final class Bound extends MethodHandle {

        private final MethodHandle target;

        private final Object bound;

        Bound(MethodType type, MethodHandle target, Object bound) {
            super(type);
            this.target = target;
            this.bound = bound;
        }

        Object call(Object[] args) throws Throwable {
            Object[] taken = new Object[args.length + 1];
            taken[0] = bound;
            for (int index = 0; index < args.length; index++) {
                taken[index + 1] = args[index];
            }
            return target.call(taken);
        }
    }

    // a handle of a variable arity, whose last parameter is an array: invoke and asType of
    // another count, or of a last argument the array does not take, put the arguments from
    // there into a new array
    private static final class Collector extends MethodHandle {

        private final MethodHandle target;

        Collector(MethodHandle target) {
            super(target.type());
            this.target = target;
        }

        Object call(Object[] args) throws Throwable {
            return target.call(args);
        }

        public boolean isVarargsCollector() {
            return true;
        }

        public MethodHandle asFixedArity() {
            return target;
        }

        public MethodHandle asType(MethodType newType) {
            MethodType own = type();
            int fixed = own.parameterCount() - 1;
            Class<?> trailing = own.parameterType(fixed);
            if (newType.parameterCount() == own.parameterCount()) {
                Class<?> last = newType.parameterType(fixed);
                // an array the trailing parameter takes: asked of an empty one
                if (newType.equals(own) || last == trailing || last.isArray()
                        && trailing.isInstance(Array.newInstance(last.getComponentType(), 0))) {
                    return target.asType(newType);
                }
            }
            Class<?> component = trailing.getComponentType();
            boolean fits = newType.parameterCount() >= fixed && converts(own.returnType(), newType.returnType());
            for (int index = 0; fits && index < newType.parameterCount(); index++) {
                fits = converts(newType.parameterType(index), index < fixed ? own.parameterType(index) : component);
            }
            if (!fits) {
                throw new WrongMethodTypeException("cannot convert " + this + " to " + newType);
            }
            return new Collecting(newType, target, fixed, component);
        }
    }

    // a call of a handle of a variable arity whose arguments from a place on are put into
    // an array of the type its last parameter takes
    private static final class Collecting extends MethodHandle {

        private final MethodHandle target;

        private final int fixed;

        private final Class<?> component;

        Collecting(MethodType type, MethodHandle target, int fixed, Class<?> component) {
            super(type);
            this.target = target;
            this.fixed = fixed;
            this.component = component;
        }

        Object call(Object[] args) throws Throwable {
            MethodType from = type();
            MethodType to = target.type();
            Object[] taken = new Object[fixed + 1];
            for (int index = 0; index < fixed; index++) {
                taken[index] = converted(args[index], from.parameterType(index), to.parameterType(index));
            }
            Object rest = Array.newInstance(component, args.length - fixed);
            for (int index = fixed; index < args.length; index++) {
                Array.set(rest, index - fixed, converted(args[index], from.parameterType(index), component));
            }
            taken[fixed] = rest;
            return converted(target.call(taken), to.returnType(), from.returnType());
        }
    }

    // the method called with the arguments its parameters take, of an object or of none;
    // what it throws is thrown as it is
    private static native Object called0(Method method, Object obj, Object[] args);

    private static native Object made0(Constructor<?> constructor, Object[] args);
}

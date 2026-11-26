// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A method a class declares. Class.getDeclaredMethods() answers each by its name and the
// class declaring it alone -- no types, no modifiers, no call -- in a program asking no
// more of one. A method found otherwise, or listed where the program asks more, has the
// rest: what it returns, takes and throws, its modifiers and the function calling it,
// which the lowering made with the class objects. Two made for one method are equal.
public final class Method extends Executable {

    private final Class<?> declaring;

    private final String name;

    // where it stands among the methods its class declares, which tells it from another
    // of the same name
    private final int index;

    // the rest of a method found or listed in full; null in one getDeclaredMethods() made
    // by name, so that a program listing names holds none of it
    private final Found found;

    private Method(Class<?> declaring, String name, int index, Found found) {
        this.declaring = declaring;
        this.name = name;
        this.index = index;
        this.found = found;
    }

    // what a method found has: what it returns, the types of its parameters and of its
    // throws clause, its modifiers with bits of the lowering's (an interface's default, a
    // class named outside its package, one of Enum's, one of a class of the JDK and why
    // nothing of it may be in the compiled program), its package and the top-level class
    // it stands in (of a class of the JDK, its module) -- who may call it is decided by
    // them -- and the function of a value and the arguments calling it, or of a value and
    // null telling whether it is one the method can be called on: of a method no call of
    // the program reaches, the test alone, a static one's null. found takes ten arguments
    // at most: a function of more takes them in a list, which each entry of a table would
    // make
    private static final class Found {

        final Class<?> returnType;

        final Class<?>[] parameterTypes;

        final Class<?>[] exceptionTypes;

        final int flags;

        final String packageName;

        final String topLevel;

        final Object invoker;

        Found(Class<?> returnType, Class<?>[] parameterTypes, Class<?>[] exceptionTypes, int flags,
                String packageName, String topLevel, Object invoker) {
            this.returnType = returnType;
            // none: the lowering writes null
            this.parameterTypes = parameterTypes == null ? new Class<?>[0] : parameterTypes;
            this.exceptionTypes = exceptionTypes == null ? new Class<?>[0] : exceptionTypes;
            this.flags = flags;
            this.packageName = packageName;
            this.topLevel = topLevel;
            this.invoker = invoker;
        }
    }

    // the lowering's bits of a member's modifiers, beside AccessibleObject's
    static final int DEFAULT = 1 << 20;

    // what Class.getDeclaredMethods() makes each of, by name
    static Method declared(Class<?> declaring, String name, int index) {
        return new Method(declaring, name, index, null);
    }

    // a method of no parameter found or listed in full that throws nothing the program asks
    static Method plain(Class<?> declaring, String name, int index, Class<?> returnType, int flags,
            String packageName, String topLevel, Object invoker) {
        return found(declaring, name, index, returnType, null, null, flags, packageName, topLevel, invoker);
    }

    // a method found or listed in full
    static Method found(Class<?> declaring, String name, int index, Class<?> returnType, Class<?>[] parameterTypes,
            Class<?>[] exceptionTypes, int flags, String packageName, String topLevel, Object invoker) {
        return new Method(declaring, name, index,
                new Found(returnType, parameterTypes, exceptionTypes, flags, packageName, topLevel, invoker));
    }

    // the methods of a class of the JDK the lowering writes as a text, an entry each: "name
    // flags returned parameters [exceptions]" (AccessibleObject.types); each called through
    // the lowering's functions of its use and its class's test (AccessibleObject.calls0)
    static Object[] jdkAll(Class<?> declaring, String text, Object[] types, String module, int classFlags,
            int test) {
        String[] entries = text.isEmpty() ? new String[0] : text.split(";");
        Object[] made = new Object[entries.length];
        for (int index = 0; index < made.length; index++) {
            String[] words = entries[index].split(" ");
            made[index] = found(declaring, words[0], index, type(types, words[2]), types(types, words[3]),
                    types(types, word(words, 4)), flags(words[1], classFlags), "", module,
                    calls0(test, use(words[1])));
        }
        return made;
    }

    // the method of no parameter of a name a class of the JDK declares, from its line of the
    // lowering's text of the methods of none (AccessibleObject.described), an entry each:
    // "name flags returned place [exceptions]"; null where it declares none of the name
    static Method jdkNamed(Class<?> declaring, String name, String text, Object[] types) {
        String line = described(text, types, declaring);
        if (line == null) {
            return unknown(declaring, name);
        }
        for (String entry : entries(line)) {
            String[] words = entry.split(" ");
            if (words[0].equals(name)) {
                int index = Integer.parseInt(words[3]);
                return found(declaring, name, index, type(types, words[2]), null, types(types, word(words, 4)),
                        flags(words[1], classFlags(line)), "", module(line), calls0(test(line), use(words[1])));
            }
        }
        return null;
    }

    // a class whose members are not kept: one of a library the program uses, the
    // library's own that is no public class of the JDK, or one of the JDK the program holds
    // only as the type of a member reflection found
    static Method unknown(Class<?> declaring, String name) {
        throw new UnsupportedOperationException("getDeclaredMethod(\"" + name + "\") of " + declaring.getName()
                + ", whose members the program does not keep, is not supported by the experimental Java subset yet");
    }

    public String getName() {
        return name;
    }

    public Class<?> getDeclaringClass() {
        return declaring;
    }

    Object member() {
        return index;
    }

    public Class<?> getReturnType() {
        return known("getReturnType()").returnType;
    }

    // what it returns as its declaration names it
    public Type getGenericReturnType() {
        Object made = Types.generic0(declaring, index, 1, this);
        return made != null ? (Type) made : getReturnType();
    }

    // what it returns as its declaration names it, with the type annotations written in it
    public AnnotatedType getAnnotatedReturnType() {
        return rontolisp.internal.AnnotatedTypes.of(getGenericReturnType(),
                rontolisp.internal.AnnotatedTypes.annotated0(declaring, index, 1));
    }

    // the type of this in it, with the annotations of its receiver parameter; none of a
    // static method
    public AnnotatedType getAnnotatedReceiverType() {
        if (Modifier.isStatic(getModifiers())) {
            return null;
        }
        return rontolisp.internal.AnnotatedTypes.of(Types.self(declaring),
                rontolisp.internal.AnnotatedTypes.annotated0(declaring, index, 4));
    }

    public TypeVariable<Method>[] getTypeParameters() {
        return Types.variables(Types.generic0(declaring, index, 0, this));
    }

    // its text as the declaration writes its types: type parameters, type arguments, type
    // variables, a variable arity's ...
    public String toGenericString() {
        Found found = known("toGenericString()");
        return Types.text(found.flags & Modifier.methodModifiers(), (found.flags & DEFAULT) != 0, getTypeParameters(),
                getGenericReturnType(), declaring, name, getGenericParameterTypes(), getGenericExceptionTypes(),
                isVarArgs());
    }

    public Class<?>[] getParameterTypes() {
        return copy(known("getParameterTypes()").parameterTypes);
    }

    public int getParameterCount() {
        return known("getParameterCount()").parameterTypes.length;
    }

    public Class<?>[] getExceptionTypes() {
        return copy(known("getExceptionTypes()").exceptionTypes);
    }

    // as Modifier decodes them, with the bit of a variable arity
    public int getModifiers() {
        return known("getModifiers()").flags & 0xFFFF;
    }

    public boolean isDefault() {
        return (known("isDefault()").flags & DEFAULT) != 0;
    }

    // a bridge a compiler made, which a JDK lists among a class of the JDK's members
    public boolean isBridge() {
        return (known("isBridge()").flags & Modifier.VOLATILE) != 0;
    }

    // a bridge of a class of the JDK's; none of one getDeclaredMethods() made by name
    public boolean isSynthetic() {
        return found != null && (found.flags & 0x1000) != 0;
    }

    static Class<?>[] copy(Class<?>[] types) {
        Class<?>[] copied = new Class<?>[types.length];
        for (int index = 0; index < types.length; index++) {
            copied[index] = types[index];
        }
        return copied;
    }

    // a call on an object, or on none for a static method, as the class calling may make
    // it: the lowering tells which class calls
    public native Object invoke(Object obj, Object... args)
            throws IllegalAccessException, InvocationTargetException;

    // invoke, called by a class of a package, of a top-level class and of a name and the
    // classes above it, which under tells an instance of
    static Object invoked(Method method, Object obj, Object[] args, String callerPackage, String callerTopLevel,
            String caller, Object under) throws IllegalAccessException, InvocationTargetException {
        Found found = method.known("invoke(..)");
        boolean isStatic = (found.flags & Modifier.STATIC) != 0;
        if (!isStatic && obj == null) {
            throw new NullPointerException();
        }
        if (!method.isAccessible()) {
            checkAccess(method.declaring, found.flags, found.packageName, found.topLevel, obj, callerPackage,
                    callerTopLevel, caller, under);
        }
        // a member of a class of the JDK nothing of which is in the compiled program: no
        // function to ask of the object
        if ((found.flags & (LACKING | ABSENT)) != 0) {
            throw new UnsupportedOperationException(missing(found.flags, "invoke",
                    method.declaring.getName() + "." + method.name + "(" + names(found.parameterTypes) + ")"));
        }
        if (!isStatic && !test0(found.invoker, obj)) {
            throw new IllegalArgumentException("object of type " + obj.getClass().getName()
                    + " is not an instance of " + method.declaring.getName());
        }
        int given = args == null ? 0 : args.length;
        int count = found.parameterTypes.length;
        if (given != count) {
            throw new IllegalArgumentException("wrong number of arguments: " + given + " expected: " + count);
        }
        if (found.invoker == null) {
            throw new UnsupportedOperationException("invoke of " + method.declaring.getName() + "." + method.name + "("
                    + names(found.parameterTypes) + ") is not supported by the experimental Java subset yet");
        }
        Object[] taken = given == 0 ? args : converted0(found.parameterTypes, args);
        try {
            return use0(found.invoker, obj, taken);
        }
        catch (Throwable thrown) {
            throw new InvocationTargetException(thrown);
        }
    }

    // whether a lookup of a class may use the method (AccessibleObject.reach)
    static int lookedUp(Method method, String callerPackage, String callerTopLevel, String caller) {
        Found found = method.known("invoke(..)");
        return reach(method.declaring, found.flags, found.packageName, found.topLevel, callerPackage, callerTopLevel,
                caller);
    }

    // the call of a method handle on the method (java.lang.invoke): who may call it was
    // decided where it was found and the arguments are the ones its parameters take; what it
    // throws is thrown as it is
    static Object handled(Method method, Object obj, Object[] args) throws Throwable {
        Found found = method.known("invoke(..)");
        if ((found.flags & (LACKING | ABSENT)) != 0) {
            throw new UnsupportedOperationException(missing(found.flags, "invoke",
                    method.declaring.getName() + "." + method.name + "(" + names(found.parameterTypes) + ")"));
        }
        if (found.invoker == null) {
            throw new UnsupportedOperationException("invoke of " + method.declaring.getName() + "." + method.name + "("
                    + names(found.parameterTypes) + ") is not supported by the experimental Java subset yet");
        }
        return use0(found.invoker, obj, args);
    }

    private Found known(String member) {
        if (found == null) {
            throw new UnsupportedOperationException("Method." + member
                    + " of a method getDeclaredMethods() answers is not supported by the experimental Java subset yet");
        }
        return found;
    }

    public boolean equals(Object other) {
        return other instanceof Method method && method.declaring == declaring && method.index == index;
    }

    // the hash code of the declaring class's name and of the method's, exclusive-or'd
    public int hashCode() {
        return declaring.getName().hashCode() ^ name.hashCode();
    }

    String closed() {
        return found == null ? null : closed(found.flags, declaring, found.topLevel, toString());
    }

    public String toString() {
        Found found = known("toString()");
        // full0(): whether the program makes any method in full; where it makes none
        // (getDeclaredMethods() asked names alone) known has thrown, and the text, which a
        // call of toString on any object reaches, is not lowered
        return full0() ? text(found.flags & Modifier.methodModifiers(), (found.flags & DEFAULT) != 0,
                found.returnType, declaring, name, found.parameterTypes, found.exceptionTypes) : null;
    }

    private static native boolean full0();
}

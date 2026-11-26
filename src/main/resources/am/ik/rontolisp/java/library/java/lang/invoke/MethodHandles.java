// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.invoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

// The lookups finding method handles: one with the access of the class asking for it, which
// the lowering tells, and the public one. A member is found over the reflection of the
// class objects (.kb/java-frontend.md, "Reflection in full"), by its name, its parameter
// types and what it returns, in the class, the classes above it and then its interfaces;
// who may use it is decided where it is found, and a handle is called without asking again.
public final class MethodHandles {

    private MethodHandles() {
    }

    // a lookup with the full access of the class calling: the lowering tells which class
    // calls
    public static native Lookup lookup();

    public static Lookup publicLookup() {
        return new Lookup(Object.class, null, null, null);
    }

    public static final class Lookup {

        public static final int PUBLIC = 1;

        public static final int PRIVATE = 2;

        public static final int PROTECTED = 4;

        public static final int PACKAGE = 8;

        public static final int MODULE = 16;

        public static final int UNCONDITIONAL = 32;

        public static final int ORIGINAL = 64;

        private final Class<?> lookupClass;

        // where the class calling is: its package, the top-level class it stands in, its
        // name and the classes above it, a space between two; null in the public lookup
        private final String callerPackage;

        private final String callerTopLevel;

        private final String caller;

        private Lookup(Class<?> lookupClass, String callerPackage, String callerTopLevel, String caller) {
            this.lookupClass = lookupClass;
            this.callerPackage = callerPackage;
            this.callerTopLevel = callerTopLevel;
            this.caller = caller;
        }

        // MethodHandles.lookup() of a class calling
        static Lookup made(Class<?> lookupClass, String callerPackage, String callerTopLevel, String caller) {
            return new Lookup(lookupClass, callerPackage, callerTopLevel, caller);
        }

        public Class<?> lookupClass() {
            return lookupClass;
        }

        public int lookupModes() {
            return callerPackage == null ? UNCONDITIONAL : PUBLIC | PRIVATE | PROTECTED | PACKAGE | MODULE | ORIGINAL;
        }

        public String toString() {
            return callerPackage == null ? "java.lang.Object/publicLookup" : lookupClass.getName();
        }

        public MethodHandle findStatic(Class<?> refc, String name, MethodType type)
                throws NoSuchMethodException, IllegalAccessException {
            Method method = resolved(refc, name, type, "invokeStatic");
            if (!Modifier.isStatic(method.getModifiers()) || access(method) == 0) {
                throw new IllegalAccessException(member("no such method: ", refc, name, type, "invokeStatic"));
            }
            return MethodHandle.method(type, method, false, null, method.isVarArgs());
        }

        // a handle taking the object first: of the lookup class where the method is a
        // protected one of another package
        public MethodHandle findVirtual(Class<?> refc, String name, MethodType type)
                throws NoSuchMethodException, IllegalAccessException {
            String kind = refc != null && refc.isInterface() ? "invokeInterface" : "invokeVirtual";
            Method method = resolved(refc, name, type, kind);
            int access = access(method);
            if (Modifier.isStatic(method.getModifiers()) || access == 0) {
                throw new IllegalAccessException(member("no such method: ", refc, name, type, kind));
            }
            return MethodHandle.method(type.insertParameterTypes(0, access == 2 ? lookupClass : refc), method, true,
                    refc.getName() + "." + name + type + "/" + kind, method.isVarArgs());
        }

        public MethodHandle findConstructor(Class<?> refc, MethodType type)
                throws NoSuchMethodException, IllegalAccessException {
            if (refc == null || type == null) {
                throw new NullPointerException();
            }
            String described = "no such constructor: " + refc.getName() + ".<init>" + type + "/newInvokeSpecial";
            if (type.returnType() != void.class || refc.isInterface() || refc.isArray() || refc.isPrimitive()) {
                throw new NoSuchMethodException(described);
            }
            visible(refc);
            for (Constructor<?> constructor : refc.getDeclaredConstructors()) {
                if (takes(constructor.getParameterTypes(), type)) {
                    if (constructed0(constructor, callerPackage, callerTopLevel, caller) == 0) {
                        throw new IllegalAccessException(described);
                    }
                    return MethodHandle.constructor(type.changeReturnType(refc), constructor,
                            constructor.isVarArgs());
                }
            }
            throw new NoSuchMethodException(described);
        }

        // a handle on a method reflection found, of the object first where it is not static
        public MethodHandle unreflect(Method m) throws IllegalAccessException {
            Class<?> declaring = m.getDeclaringClass();
            boolean instance = !Modifier.isStatic(m.getModifiers());
            MethodType type = MethodType.methodType(m.getReturnType(), m.getParameterTypes());
            String kind = !instance ? "invokeStatic" : declaring.isInterface() ? "invokeInterface" : "invokeVirtual";
            int access = m.isAccessible() ? 1 : access(m);
            if (access == 0) {
                throw new IllegalAccessException(member("no such method: ", declaring, m.getName(), type, kind));
            }
            return MethodHandle.method(
                    instance ? type.insertParameterTypes(0, access == 2 ? lookupClass : declaring) : type, m, instance,
                    declaring.getName() + "." + m.getName() + type + "/" + kind, m.isVarArgs());
        }

        public MethodHandle unreflectConstructor(Constructor<?> c) throws IllegalAccessException {
            Class<?> declaring = c.getDeclaringClass();
            MethodType type = MethodType.methodType(void.class, c.getParameterTypes());
            if (!c.isAccessible() && constructed0(c, callerPackage, callerTopLevel, caller) == 0) {
                throw new IllegalAccessException(
                        "no such constructor: " + declaring.getName() + ".<init>" + type + "/newInvokeSpecial");
            }
            return MethodHandle.constructor(type.changeReturnType(declaring), c, c.isVarArgs());
        }

        // the method of a name a class has of those types returning that type: its own or a
        // class above it's, else one of its interfaces', else Object's for an interface
        private Method resolved(Class<?> refc, String name, MethodType type, String kind)
                throws NoSuchMethodException, IllegalAccessException {
            if (refc == null || name == null || type == null) {
                throw new NullPointerException();
            }
            if (name.startsWith("<")) {
                throw new NoSuchMethodException("illegal method name: " + name);
            }
            visible(refc);
            for (Class<?> each = refc; each != null; each = each.getSuperclass()) {
                Method own = among(each, name, type);
                if (own != null) {
                    return own;
                }
            }
            Method inherited = inherited(refc, name, type);
            if (inherited == null && refc.isInterface()) {
                inherited = among(Object.class, name, type);
            }
            if (inherited == null) {
                throw new NoSuchMethodException(member("no such method: ", refc, name, type, kind));
            }
            return inherited;
        }

        private static Method inherited(Class<?> type, String name, MethodType wanted) {
            for (Class<?> each = type; each != null; each = each.getSuperclass()) {
                for (Class<?> implemented : each.getInterfaces()) {
                    Method own = among(implemented, name, wanted);
                    if (own == null) {
                        own = inherited(implemented, name, wanted);
                    }
                    if (own != null) {
                        return own;
                    }
                }
            }
            return null;
        }

        private static Method among(Class<?> type, String name, MethodType wanted) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getReturnType() == wanted.returnType()
                        && takes(method.getParameterTypes(), wanted)) {
                    return method;
                }
            }
            return null;
        }

        private static boolean takes(Class<?>[] parameters, MethodType wanted) {
            if (parameters.length != wanted.parameterCount()) {
                return false;
            }
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] != wanted.parameterType(index)) {
                    return false;
                }
            }
            return true;
        }

        // whether the lookup may use a method: 0 not, 1 yes, 2 as a protected method of
        // another package, on objects of the lookup class
        private int access(Method method) {
            return looked0(method, callerPackage, callerTopLevel, caller);
        }

        // a class the lookup may name: public, or of the lookup class's package
        private void visible(Class<?> refc) throws IllegalAccessException {
            Class<?> element = refc;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            if (element.isPrimitive() || Modifier.isPublic(element.getModifiers())
                    || callerPackage != null && element.getPackageName().equals(callerPackage)) {
                return;
            }
            String from = "public Lookup";
            if (callerPackage != null) {
                // the unnamed module's identity, in hexadecimal digits written here
                String hash = "";
                for (int bits = System.identityHashCode(lookupClass); hash.isEmpty() || bits != 0; bits >>>= 4) {
                    hash = "0123456789abcdef".charAt(bits & 15) + hash;
                }
                from = "class " + lookupClass.getName() + " (unnamed module @" + hash + ")";
            }
            throw new IllegalAccessException(
                    "symbolic reference class is not accessible: class " + refc.getName() + ", from " + from);
        }

        private static String member(String what, Class<?> refc, String name, MethodType type, String kind) {
            return what + refc.getName() + "." + name + type + "/" + kind;
        }
    }

    // whether a class calling may use a method or a constructor where a lookup finds it
    private static native int looked0(Method method, String callerPackage, String callerTopLevel, String caller);

    private static native int constructed0(Constructor<?> constructor, String callerPackage, String callerTopLevel,
            String caller);
}

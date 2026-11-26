// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.util.introspection.IntrospectionError;

// A value's property or field asked by its name, as the checks of AssertJ ask it (what
// its 3.27 answers under a JDK): a getter of the name (getName, else isName) first, then
// a field of the class or of a class above it, static and synthetic ones aside; a dotted
// name is a walk, a null on the way its value.
public final class Introspected {

    private Introspected() {
    }

    // The value of a property or a field of an object by its name, which may be dotted.
    public static Object value(Object target, String name) {
        Object value = target;
        int from = 0;
        while (value != null) {
            int dot = name.indexOf('.', from);
            value = one(value, dot < 0 ? name.substring(from) : name.substring(from, dot));
            if (dot < 0) {
                return value;
            }
            from = dot + 1;
        }
        return null;
    }

    // Whether an object has a property or a field of a name, which may be dotted.
    public static boolean has(Object target, String name) {
        try {
            value(target, name);
            return true;
        }
        catch (IntrospectionError absent) {
            return false;
        }
    }

    private static Object one(Object target, String name) {
        Method getter = getter(target.getClass(), name);
        if (getter != null) {
            try {
                getter.setAccessible(true);
                return getter.invoke(target);
            }
            catch (ReflectiveOperationException failed) {
                throw new IntrospectionError("Unable to obtain the value of the property <'" + name + "'> from <" + target
                        + ">", failed);
            }
        }
        Field field = field(target.getClass(), name);
        if (field == null) {
            throw new IntrospectionError("\nCan't find any field or property with name '" + name
                    + "'.\nError when introspecting properties was :\n- No getter for property '" + name + "' in "
                    + target.getClass().getName() + " \nError when introspecting fields was :\n- Unable to obtain the value"
                    + " of the field <'" + name + "'> from <" + target + ">");
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        }
        catch (ReflectiveOperationException failed) {
            throw new IntrospectionError("Unable to obtain the value of the field <'" + name + "'> from <" + target + ">",
                    failed);
        }
    }

    private static Method getter(Class<?> type, String name) {
        if (name.isEmpty()) {
            return null;
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[] { "get", "is" }) {
            try {
                Method method = type.getMethod(prefix + suffix);
                if (!Modifier.isStatic(method.getModifiers())) {
                    return method;
                }
            }
            catch (NoSuchMethodException absent) {
                // the other prefix, then a field
            }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            try {
                Field field = each.getDeclaredField(name);
                if (kept(field)) {
                    return field;
                }
            }
            catch (NoSuchFieldException absent) {
                // a field of the class above
            }
        }
        return null;
    }

    private static boolean kept(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic();
    }

    // The names of the instance fields of a class and of the classes above it, each once:
    // a class of the JDK's adds none (its fields are its own, compared by equals); a class
    // of a library whose members the program does not keep is refused.
    public static List<String> fieldNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            Object[] declared = fieldNames0(each);
            if (declared == null) {
                if (each.getName().startsWith("java.")) {
                    break;
                }
                throw new UnsupportedOperationException("the fields of " + each.getName() + ", whose members the program"
                        + " does not keep, are not supported by the experimental Java subset yet");
            }
            for (Object name : declared) {
                if (!names.contains(name)) {
                    names.add((String) name);
                }
            }
        }
        return names;
    }

    // The names of the instance fields a class of the program declares, in the order
    // declared, or null for any other class: names alone, which no member table makes.
    private static native Object[] fieldNames0(Class<?> type);
}

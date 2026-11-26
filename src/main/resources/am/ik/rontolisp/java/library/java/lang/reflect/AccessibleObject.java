// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

import java.lang.annotation.Annotation;

// What a member found by reflection keeps of whether the program asked to use it as if it
// were accessible: a call or a read of one is then not checked against the class making
// it. A member of the program's classes and of its libraries may always be made so; one
// of a class of the JDK where it is public in a public class, as a JDK opens none of its
// packages to a program.
public class AccessibleObject implements AnnotatedElement {

    // the lowering's bits of a member's flags, above the modifiers: its class can be
    // named outside its package; it is a member of a class of the JDK (whose module the
    // member holds where another holds the top-level class it stands in), of which the
    // library has no implementation, or one the compiled program holds none of
    static final int VISIBLE = 1 << 21;

    static final int JDK = 1 << 26;

    static final int LACKING = 1 << 27;

    static final int ABSENT = 1 << 28;


    private boolean accessible;

    protected AccessibleObject() {
    }

    public boolean isAccessible() {
        return accessible;
    }

    public void setAccessible(boolean flag) {
        if (flag) {
            String refused = closed();
            if (refused != null) {
                throw new InaccessibleObjectException(refused);
            }
        }
        accessible = flag;
    }

    public final boolean trySetAccessible() {
        if (closed() != null) {
            return false;
        }
        accessible = true;
        return true;
    }

    public static void setAccessible(AccessibleObject[] array, boolean flag) {
        for (AccessibleObject each : array) {
            each.setAccessible(flag);
        }
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return getDeclaredAnnotation(annotationClass);
    }

    public Annotation[] getAnnotations() {
        return getDeclaredAnnotations();
    }

    // a member's own annotations: a field, a method and a constructor answer theirs
    public Annotation[] getDeclaredAnnotations() {
        throw new AssertionError("All subclasses should override this method");
    }

    // new annotations of a class (member null) or of a member of it -- a method by its
    // place among those getDeclaredMethods() lists, a constructor by -1 less its place, a
    // field or a record's component by its name --: 0 its own, 1 a class's with the
    // inherited ones, 2 each parameter's of a method or a constructor (those a compiler
    // adds left out), 3 a record component's; null for none
    static native Object annotations0(Class<?> declaring, Object member, int what);

    static Annotation[] annotations(Object made) {
        return rontolisp.internal.Annotations.listed(made);
    }

    // what setAccessible(true) of a member a program may not open says, else null
    String closed() {
        return null;
    }

    // the words refusing to open a member of a class of the JDK that is not public or is
    // of a class that is not: the JDK opens none of its packages to the program's module
    static String closed(int flags, Class<?> declaring, String module, String member) {
        if ((flags & JDK) == 0 || ((flags & Modifier.PUBLIC) != 0 && (flags & VISIBLE) != 0)) {
            return null;
        }
        // the module's identity, in hexadecimal digits written here: Integer's would bring
        // the printer of numbers in a base
        String hash = "";
        for (int bits = System.identityHashCode(declaring); hash.isEmpty() || bits != 0; bits >>>= 4) {
            hash = "0123456789abcdef".charAt(bits & 15) + hash;
        }
        return "Unable to make " + member + " accessible: module " + module + " does not \"opens "
                + declaring.getPackageName() + "\" to unnamed module @" + hash;
    }

    // whether the class calling a member may, when the program did not ask for its use:
    // the flags are the member's modifiers as Modifier spells them (public 1, private 2,
    // protected 4, static 8 ..) with the bits above. The lowering tells of the class
    // calling its package, the top-level class it stands in, its name and the classes
    // above it (each after a space), and the function telling whether a value is an
    // instance of that class: a protected member of another package is the class's where
    // the class is under the member's and the object it is used on is under the class --
    // a static one's on any, a constructor's (whose under is null) never
    static void checkAccess(Class<?> declaring, int flags, String packageName, String topLevel, Object target,
            String callerPackage, String callerTopLevel, String caller, Object under)
            throws IllegalAccessException {
        int modifiers = flags & 0xFFFF;
        boolean jdk = (flags & JDK) != 0;
        boolean samePackage = !jdk && packageName.equals(callerPackage);
        boolean allowed;
        if (!jdk && topLevel.equals(callerTopLevel)) {
            allowed = true;
        }
        else if ((flags & VISIBLE) == 0 && !samePackage) {
            allowed = false;
        }
        else if ((modifiers & Modifier.PUBLIC) != 0) {
            allowed = true;
        }
        else if ((modifiers & Modifier.PRIVATE) != 0) {
            allowed = false;
        }
        else if ((modifiers & Modifier.PROTECTED) != 0 && !samePackage) {
            allowed = under != null && (" " + caller + " ").indexOf(" " + declaring.getName() + " ") >= 0
                    && ((modifiers & Modifier.STATIC) != 0 || test0(under, target));
        }
        else {
            allowed = samePackage;
        }
        if (!allowed) {
            int space = caller.indexOf(' ');
            throw new IllegalAccessException("class " + (space < 0 ? caller : caller.substring(0, space))
                    + " cannot access a member of class " + declaring.getName()
                    + (jdk ? " (in module " + topLevel + ")" : "") + " with modifiers \""
                    + Modifier.toString(modifiers) + "\"");
        }
    }

    // whether a lookup of a class (MethodHandles.Lookup, where it finds a member) may use a
    // member: 0 not, 1 yes, 2 as a protected member of another package, whose object has
    // to be one of the lookup class's. The public lookup (no caller) uses a public member
    // of a class that can be named outside its package
    static int reach(Class<?> declaring, int flags, String packageName, String topLevel, String callerPackage,
            String callerTopLevel, String caller) {
        int modifiers = flags & 0xFFFF;
        if (callerPackage == null) {
            return (modifiers & Modifier.PUBLIC) != 0 && (flags & VISIBLE) != 0 ? 1 : 0;
        }
        boolean jdk = (flags & JDK) != 0;
        boolean samePackage = !jdk && packageName.equals(callerPackage);
        if (!jdk && topLevel.equals(callerTopLevel)) {
            return 1;
        }
        if ((flags & VISIBLE) == 0 && !samePackage) {
            return 0;
        }
        if ((modifiers & Modifier.PUBLIC) != 0) {
            return 1;
        }
        if ((modifiers & Modifier.PRIVATE) != 0) {
            return 0;
        }
        if (samePackage) {
            return 1;
        }
        if ((modifiers & Modifier.PROTECTED) != 0
                && (" " + caller + " ").indexOf(" " + declaring.getName() + " ") >= 0) {
            return (modifiers & Modifier.STATIC) != 0 ? 1 : 2;
        }
        return 0;
    }

    // the words refusing a use of a member of a class of the JDK where nothing of it is in
    // the compiled program: the library has no implementation of it, or the program calls
    // it nowhere, which reflection does not add to what it holds
    static String missing(int flags, String use, String member) {
        return use + " of " + member + ((flags & LACKING) != 0
                ? " is not supported by the experimental Java subset yet"
                : " is not supported by the experimental Java subset where the program uses it only by reflection");
    }

    // a class of the JDK's line of the lowering's text of its classes' members of a kind,
    // a line a class by the place of its class object among those the texts name: "+" of a
    // public class or "-", the number of its test where a member of it is used, its module
    // where it is not java.base, "|", then its members; null where the text describes none
    // of the class
    static String described(String text, Object[] types, Class<?> declaring) {
        int start = 0;
        for (int place = 0; place < types.length; place++) {
            int end = text.indexOf('\n', start);
            if (types[place] == declaring) {
                String line = end < 0 ? text.substring(start) : text.substring(start, end);
                return line.isEmpty() ? null : line;
            }
            if (end < 0) {
                return null;
            }
            start = end + 1;
        }
        return null;
    }

    static String module(String line) {
        int start = headed(line);
        int bar = line.indexOf('|');
        return bar == start ? "java.base" : line.substring(start, bar);
    }

    static int test(String line) {
        int end = headed(line);
        return end == 1 ? -1 : Integer.parseInt(line.substring(1, end));
    }

    // where the digits of a line's test end
    private static int headed(String line) {
        int end = 1;
        while (line.charAt(end) >= '0' && line.charAt(end) <= '9') {
            end++;
        }
        return end;
    }

    // the number of a member's use, after the 'u' of its flags where the program holds it
    static int use(String flags) {
        int at = flags.indexOf('u');
        return at < 0 ? -1 : Integer.parseInt(flags.substring(at + 1));
    }

    // a word of an entry the lowering leaves out where it is empty
    static String word(String[] words, int at) {
        return at < words.length ? words[at] : "-";
    }

    static int classFlags(String line) {
        return JDK | (line.charAt(0) == '+' ? VISIBLE : 0);
    }

    static String[] entries(String line) {
        String members = line.substring(line.indexOf('|') + 1);
        return members.isEmpty() ? new String[0] : members.split(";");
    }

    // the members of a class of the JDK as the lowering writes them, a text an entry each
    // (';' between two, a space between its words) where the program runs: types by their
    // places among the class objects of the program's table (-1 none, '-' an empty list)
    static Class<?>[] types(Object[] types, String places) {
        if (places.equals("-")) {
            return null;
        }
        String[] written = places.split(",");
        Class<?>[] found = new Class<?>[written.length];
        for (int index = 0; index < found.length; index++) {
            int place = Integer.parseInt(written[index]);
            found[index] = place < 0 ? null : (Class<?>) types[place];
        }
        return found;
    }

    static Class<?> type(Object[] types, String place) {
        int at = Integer.parseInt(place);
        return at < 0 ? null : (Class<?>) types[at];
    }

    // a member's flags as the lowering writes them: its modifiers, then a letter for each
    // bit of the lowering's -- d an interface's default, l of no implementation, a held by
    // no part of the program --, with its class's bits; then its use (AccessibleObject.use)
    static int flags(String written, int classFlags) {
        int end = 0;
        while (end < written.length() && written.charAt(end) <= '9') {
            end++;
        }
        int flags = Integer.parseInt(written.substring(0, end)) | classFlags;
        for (int at = end; at < written.length() && written.charAt(at) != 'u'; at++) {
            char letter = written.charAt(at);
            flags |= letter == 'd' ? 1 << 20 : letter == 'l' ? LACKING : ABSENT;
        }
        return flags;
    }

    // the function of a value and the arguments a method of a class of the JDK is called
    // through, and of a value and a mode its field is read and written through: the
    // lowering's functions of a number, of a value and the rest a member's use, of a value
    // alone its class's test, told the class's test and the member's use (-1 none)
    static native Object calls0(int test, int use);

    static native Object reads0(int test, int use);

    // whether a value is one the member can be used on: an instance of its class, any
    // value for a static one
    static native boolean test0(Object member, Object value);

    // the member used on a value: a method called with the arguments its parameters take,
    // a field read
    static native Object use0(Object member, Object value, Object[] args);

    // a field of a value written
    static native void write0(Object member, Object value, Object given);
}

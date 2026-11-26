// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// The bits of a class's or a member's modifiers, their values the specification's
// constants, the words in the order a JDK writes them.
public final class Modifier {

    public static final int PUBLIC = 0x1;

    public static final int PRIVATE = 0x2;

    public static final int PROTECTED = 0x4;

    public static final int STATIC = 0x8;

    public static final int FINAL = 0x10;

    public static final int SYNCHRONIZED = 0x20;

    public static final int VOLATILE = 0x40;

    public static final int TRANSIENT = 0x80;

    public static final int NATIVE = 0x100;

    public static final int INTERFACE = 0x200;

    public static final int ABSTRACT = 0x400;

    public static final int STRICT = 0x800;

    private Modifier() {
    }

    public static boolean isPublic(int mod) {
        return (mod & PUBLIC) != 0;
    }

    public static boolean isPrivate(int mod) {
        return (mod & PRIVATE) != 0;
    }

    public static boolean isProtected(int mod) {
        return (mod & PROTECTED) != 0;
    }

    public static boolean isStatic(int mod) {
        return (mod & STATIC) != 0;
    }

    public static boolean isFinal(int mod) {
        return (mod & FINAL) != 0;
    }

    public static boolean isSynchronized(int mod) {
        return (mod & SYNCHRONIZED) != 0;
    }

    public static boolean isVolatile(int mod) {
        return (mod & VOLATILE) != 0;
    }

    public static boolean isTransient(int mod) {
        return (mod & TRANSIENT) != 0;
    }

    public static boolean isNative(int mod) {
        return (mod & NATIVE) != 0;
    }

    public static boolean isInterface(int mod) {
        return (mod & INTERFACE) != 0;
    }

    public static boolean isAbstract(int mod) {
        return (mod & ABSTRACT) != 0;
    }

    public static boolean isStrict(int mod) {
        return (mod & STRICT) != 0;
    }

    public static String toString(int mod) {
        // the words in the order the specification writes them, each after a character
        // whose value is its bit's place: one string where two arrays would cost each
        // program reaching it four times as much
        String words = "\0public\2protected\1private\12abstract\3static\4final\7transient\6volatile\5synchronized"
                + "\10native\13strictfp\11interface";
        StringBuilder written = new StringBuilder();
        boolean kept = false;
        for (int index = 0; index < words.length(); index++) {
            char at = words.charAt(index);
            if (at < ' ') {
                kept = (mod & (1 << at)) != 0;
                if (kept && written.length() > 0) {
                    written.append(' ');
                }
            }
            else if (kept) {
                written.append(at);
            }
        }
        return written.toString();
    }

    public static int classModifiers() {
        return PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | FINAL | STRICT;
    }

    public static int interfaceModifiers() {
        return PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | STRICT;
    }

    public static int constructorModifiers() {
        return PUBLIC | PROTECTED | PRIVATE;
    }

    public static int methodModifiers() {
        return PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | FINAL | SYNCHRONIZED | NATIVE | STRICT;
    }

    public static int fieldModifiers() {
        return PUBLIC | PROTECTED | PRIVATE | STATIC | FINAL | TRANSIENT | VOLATILE;
    }

    public static int parameterModifiers() {
        return FINAL;
    }
}

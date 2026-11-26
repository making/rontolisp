// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

// Permission bits as the nine characters ls prints, rwxrwxrwx with a dash for each one
// not given, and as the attribute a file is made with.
public final class PosixFilePermissions {

    private static final String LETTERS = "rwxrwxrwx";

    private PosixFilePermissions() {
    }

    public static String toString(Set<PosixFilePermission> perms) {
        StringBuilder text = new StringBuilder(9);
        for (PosixFilePermission perm : PosixFilePermission.values()) {
            text.append(perms.contains(perm) ? LETTERS.charAt(perm.ordinal()) : '-');
        }
        return text.toString();
    }

    // A letter out of its place, or a text of other than nine characters, is refused.
    public static Set<PosixFilePermission> fromString(String perms) {
        if (perms.length() != 9) {
            throw new IllegalArgumentException("Invalid mode");
        }
        Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission perm : PosixFilePermission.values()) {
            char c = perms.charAt(perm.ordinal());
            if (c == LETTERS.charAt(perm.ordinal())) {
                set.add(perm);
            }
            else if (c != '-') {
                throw new IllegalArgumentException("Invalid mode");
            }
        }
        return set;
    }

    // The attribute posix:permissions of a copy of the set, which may not be changed; a
    // null in it is refused.
    public static FileAttribute<Set<PosixFilePermission>> asFileAttribute(Set<PosixFilePermission> perms) {
        Set<PosixFilePermission> copy = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission perm : perms) {
            copy.add(Objects.requireNonNull(perm));
        }
        return new Permissions(Collections.unmodifiableSet(copy));
    }

    private static final class Permissions implements FileAttribute<Set<PosixFilePermission>> {
        private final Set<PosixFilePermission> perms;

        Permissions(Set<PosixFilePermission> perms) {
            this.perms = perms;
        }

        public String name() {
            return "posix:permissions";
        }

        public Set<PosixFilePermission> value() {
            return perms;
        }
    }
}

// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

// The host's file system as the file classes of the library reach it: a path is a String
// as the program spelled it, relative ones resolved by the host against its working
// directory (a .wasm: the directory its first preopen names), and a file opened is an
// Object no class looks into. What fails answers null or false; the classes above say
// why, as a JDK words it.
public final class Disk {

    // the modes of open: reading; writing from the start, the file made or emptied;
    // writing at the end, the file made; reading and writing over what is there; writing
    // from the start over what is there. The last two open a file that is there.
    public static final int READ = 0;

    public static final int WRITE = 1;

    public static final int APPEND = 2;

    public static final int UPDATE = 3;

    public static final int OVERWRITE = 4;

    // what kind answers: nothing, a file (anything that is no directory), a directory
    public static final int NONE = 0;

    public static final int FILE = 1;

    public static final int DIRECTORY = 2;

    // what refused reports
    public static final int MISSING = 0;

    public static final int IS_DIRECTORY = 1;

    public static final int NOT_DIRECTORY = 2;

    public static final int DENIED = 3;

    private Disk() {
    }

    // What a path names; a link counts as what it points to.
    public static native int kind(String path);

    public static native Object open(String path, int mode);

    // A byte, -1 at the end.
    public static native int read(Object file);

    // How many bytes went into b[off, off + len), -1 at the end; len is above 0.
    public static native int read(Object file, byte[] b, int off, int len);

    // A write reaches the file before it answers, as a JDK's unbuffered streams do.
    public static native void write(Object file, int b);

    public static native void write(Object file, byte[] b, int off, int len);

    public static native void close(Object file);

    public static native long position(Object file);

    public static native void seek(Object file, long position);

    public static native long length(Object file);

    // The size of the file a path names, -1 where it cannot be read.
    public static native long size(String path);

    // When the file a path names was last written, in milliseconds since 1970 (whole
    // seconds), 0 where the host does not say.
    public static native long modified(String path);

    // The names in a directory in the host's order, null for anything that is no
    // directory it can read.
    public static native String[] names(String path);

    // Makes a directory and every one above it that is missing: whether it is one now.
    public static native boolean makeDirectories(String path);

    // Deletes a file or an empty directory.
    public static native boolean delete(String path);

    public static native boolean rename(String from, String to);

    // The working directory, null where the host has none to name (a .wasm whose
    // first preopen has a relative name).
    public static native String workingDirectory();

    // An absolute path with its links followed and its . and .. taken out; a part that
    // is not there is kept as it is spelled.
    public static native String real(String path);

    // Opens a file, making it first for a mode that opens one that is there.
    public static Object opened(String path, int mode) {
        if ((mode == UPDATE || mode == OVERWRITE) && kind(path) == NONE) {
            Object made = open(path, APPEND);
            if (made == null) {
                return null;
            }
            close(made);
        }
        return open(path, mode);
    }

    // Why a path could not be opened, or made where it is not: a directory is there, a
    // file is there that may not be read or written, or nothing is, and the nearest
    // thing above it is a file, or the directory it would be made in may not be written,
    // or something above is missing.
    public static int refused(String path, boolean making) {
        int kind = kind(path);
        if (kind == DIRECTORY) {
            return IS_DIRECTORY;
        }
        if (kind == FILE) {
            return DENIED;
        }
        boolean parent = true;
        String above = above(path);
        while (above != null) {
            int at = kind(above);
            if (at == FILE) {
                return NOT_DIRECTORY;
            }
            if (at == DIRECTORY) {
                return parent && (making || listed(above, path)) ? DENIED : MISSING;
            }
            parent = false;
            above = above(above);
        }
        // the working directory is above a relative path, the root above any other
        return parent && !path.isEmpty() && (making || listed(".", path)) ? DENIED : MISSING;
    }

    // Whether a directory lists the last name of a path: a file a .wasm may not open is
    // nothing to kind, which asks the host by opening it.
    private static boolean listed(String directory, String path) {
        String[] names = names(directory);
        if (names != null) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            for (String listed : names) {
                if (listed.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The words a JDK puts after a file's path for each refusal.
    public static String words(int refusal) {
        return refusal == IS_DIRECTORY ? "Is a directory"
                : refusal == NOT_DIRECTORY ? "Not a directory"
                        : refusal == DENIED ? "Permission denied" : "No such file or directory";
    }

    // One slash for each run of them, and none at the end but the root's.
    public static String normal(String path) {
        int n = path.length();
        int i = 0;
        while (i < n && !(path.charAt(i) == '/' && (i + 1 == n || path.charAt(i + 1) == '/'))) {
            i++;
        }
        if (i == n) {
            return path;
        }
        StringBuilder kept = new StringBuilder(n);
        kept.append(path, 0, i);
        boolean slash = false;
        for (; i < n; i++) {
            char c = path.charAt(i);
            if (c == '/') {
                slash = true;
                continue;
            }
            if (slash) {
                kept.append('/');
                slash = false;
            }
            kept.append(c);
        }
        if (slash && kept.length() == 0) {
            kept.append('/');
        }
        return kept.toString();
    }

    // The path a path is in, or null: what precedes its last /, the root where that is
    // the first character.
    public static String above(String path) {
        int at = path.lastIndexOf('/');
        if (at < 0 || path.length() == 1) {
            return null;
        }
        return at == 0 ? "/" : path.substring(0, at);
    }
}

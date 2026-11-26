// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Random;

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

    // the kinds stat answers: nothing, a regular file, a directory, a symbolic link
    // (where not following it), anything else (a device, a pipe, a socket)
    public static final int NONE = 0;

    public static final int FILE = 1;

    public static final int DIRECTORY = 2;

    public static final int LINK = 3;

    public static final int OTHER = 4;

    // what a stat of a path that is NONE says of why: nothing is there (or something above
    // is no directory), the host refused, too many links, another reason
    public static final int NOT_THERE = 1;

    public static final int REFUSED = 2;

    public static final int LOOPED = 3;

    // the slots of a stat: the kind (or NONE and why), the size, the times of last
    // writing, last access and making in nanoseconds since 1970, and the permission bits
    // (07777; -1 where the host has none)
    public static final int KIND = 0;

    public static final int WHY = 1;

    public static final int SIZE = 1;

    public static final int MODIFIED = 2;

    public static final int ACCESSED = 3;

    public static final int MADE = 4;

    public static final int MODE = 5;

    // what refused reports
    public static final int MISSING = 0;

    public static final int IS_DIRECTORY = 1;

    public static final int NOT_DIRECTORY = 2;

    public static final int DENIED = 3;

    public static final int LOOP = 4;

    // the words a JDK's java.nio.file puts after a path where the host said ELOOP
    public static final String LOOP_WORDS = "Too many levels of symbolic links or unable to access attributes of symbolic link";

    // where a temporary file is made where no directory is given
    public static final String TEMPORARY = "/tmp";

    // the numbers of the names of temporary files, made at the first ask
    private static Random unlikely;

    private Disk() {
    }

    // A name no file is likely to have: the prefix, a random number of 64 bits read
    // unsigned and the suffix.
    public static String unlikely(String prefix, String suffix) {
        if (unlikely == null) {
            unlikely = new Random();
        }
        return prefix + Long.toUnsignedString(unlikely.nextLong()) + suffix;
    }

    // What the host's stat (following a link in the last name) or lstat says of a path,
    // with no file opened: {kind, size, modified, accessed, made, mode}, the times in
    // nanoseconds since 1970 (the time of making the time of last writing where the host
    // does not say), the permission bits -1 where the host has none; or {NONE, why}.
    public static native long[] stat(String path, boolean follow);

    // Whether the host keeps permission bits: a WASI host keeps none, and the classes
    // above answer there as a JDK does on a file system without them.
    public static native boolean modes();

    // Whether the host lets the program read (4), write (2) or execute (1; a directory:
    // search) what a path names, its links followed (access(2)); where it keeps no
    // permission bits it is not asked.
    public static native boolean access(String path, int what);

    // The size of the partition holding a path (0), the bytes free in it (1) or those
    // this program may use (2): 0 where the path names nothing, or the host does not say.
    public static native long space(String path, int which);

    // Gives what a path names, its links followed, the permission bits (07777): 0, or
    // why not (stat's why).
    public static native int setMode(String path, int mode);

    // Makes a file or a directory with the permission bits (0777) less the process's
    // umask, as a JDK makes one with a FileAttribute: 0, or why not.
    public static native int make(String path, int mode, boolean directory);

    // Gives a path its times of last writing and of last access in nanoseconds since
    // 1970, by the path (a file the program may not read takes them too), null leaving
    // one as it is: 0, or why not (stat's why).
    public static native int setTimes(String path, boolean follow, Long modified, Long accessed);

    // What the host's stat (or lstat) says of a path beyond the basic view: {uid, gid, ino,
    // dev, rdev, nlink, ctime, mode}, the time its status last changed in nanoseconds since
    // 1970 and the mode with the bits of its kind; or {why} (stat's). A host without
    // permission bits keeps none of it and is not asked. (The slots are Files.Unix's: a
    // static final here would be a global of every program reaching this class.)
    public static native long[] unix(String path, boolean follow);

    // The names of a path's owner and group as the host's user database has them (the
    // number where it has none), or null where its stat cannot say.
    public static native String[] owners(String path, boolean follow);

    // Gives a path an owner and a group by number, -1 leaving one as it is (a link not
    // followed is given them itself): 0, or why not (stat's, or 5 where the host does not
    // allow it, EPERM).
    public static native int setOwner(String path, boolean follow, int uid, int gid);

    // What a path names, its links followed.
    public static int kind(String path) {
        return (int) stat(path, true)[KIND];
    }

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

    // The names in a directory in the host's order, null for anything that is no
    // directory it can read.
    public static native String[] names(String path);

    // Makes a directory and every one above it that is missing: whether it is one now.
    public static native boolean makeDirectories(String path);

    // Deletes a file or an empty directory.
    public static native boolean delete(String path);

    public static native boolean rename(String from, String to);

    // The target the symbolic link at a path holds, as the link spells it; null for
    // anything that is no link, or one the host will not read (a WASI host's whose
    // target is absolute).
    public static native String readLink(String path);

    // Makes a symbolic link at a path holding a target as given: whether it was made (a
    // WASI host makes none whose target is absolute).
    public static native boolean link(String target, String path);

    // The working directory, null where the host has none to name (a .wasm whose
    // first preopen has a relative name).
    public static native String workingDirectory();

    public static String workingDirectoryOr(String otherwise) {
        String directory = workingDirectory();
        return directory == null ? otherwise : directory;
    }

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
    // file is there that may not be read or written, the host will not look into
    // something above it, too many links lead from it, or nothing is there, and the
    // nearest thing above it is no directory, or the directory it would be made in may
    // not be written, or something above is missing.
    public static int refused(String path, boolean making) {
        long[] stat = stat(path, true);
        int kind = (int) stat[KIND];
        if (kind == DIRECTORY) {
            return IS_DIRECTORY;
        }
        if (kind != NONE || stat[WHY] == REFUSED) {
            return DENIED;
        }
        if (stat[WHY] == LOOPED) {
            return LOOP;
        }
        boolean parent = true;
        String above = above(path);
        while (above != null) {
            int at = kind(above);
            if (at == DIRECTORY) {
                return parent && making ? DENIED : MISSING;
            }
            if (at != NONE) {
                return NOT_DIRECTORY;
            }
            parent = false;
            above = above(above);
        }
        // the working directory is above a relative path, the root above any other
        return parent && !path.isEmpty() && making ? DENIED : MISSING;
    }

    // The words a JDK puts after a file's path for each refusal.
    public static String words(int refusal) {
        return refusal == IS_DIRECTORY ? "Is a directory"
                : refusal == NOT_DIRECTORY ? "Not a directory"
                        : refusal == DENIED ? "Permission denied"
                                : refusal == LOOP ? "Too many levels of symbolic links" : "No such file or directory";
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

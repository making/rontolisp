// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import rontolisp.internal.Disk;
import rontolisp.internal.Glob;
import rontolisp.internal.LocalPath;
import rontolisp.internal.ObjectFlow;
import rontolisp.internal.StrictReader;
import rontolisp.internal.StrictWriter;

// The file system's operations over the streams of java.io and the host's own
// (rontolisp.internal.Disk). The empty path is the working directory here, as a JDK's is.
// Text is read and written strictly: bytes that are no text in the charset are a
// MalformedInputException, a character it cannot write a MalformedInputException or an
// UnmappableCharacterException, each with how many there are as a JDK counts them. What
// each operation refuses, which exception and which path it names, is a JDK's on a Unix
// host, read off its answers. What a path is, its size and its times are the host's stat
// (lstat where a link is not followed); an operation that makes, moves, copies over or
// deletes a path sees a link as itself.
public final class Files {

    private Files() {
    }

    private static String host(Path path) {
        return ((LocalPath) Objects.requireNonNull(path)).host();
    }

    private static int kind(Path path) {
        return Disk.kind(host(path));
    }

    // What a path is where a link in its last name is not followed: a link is LINK.
    private static int own(Path path) {
        return (int) Disk.stat(host(path), false)[Disk.KIND];
    }

    // Whether the options leave a link in the last name to be followed.
    private static boolean follows(LinkOption... options) {
        for (LinkOption option : options) {
            if (Objects.requireNonNull(option) == LinkOption.NOFOLLOW_LINKS) {
                return false;
            }
        }
        return true;
    }

    private static long[] stat(Path path, LinkOption... options) {
        return Disk.stat(host(path), follows(options));
    }

    // The exception a JDK throws where the host refused a path: an operation that makes
    // it is refused where it could not be made.
    private static IOException refusal(Path path, boolean making) {
        String file = path.toString();
        int refused = Disk.refused(host(path), making);
        return refused == Disk.MISSING ? new NoSuchFileException(file)
                : refused == Disk.DENIED ? new AccessDeniedException(file)
                        : new FileSystemException(file, null,
                                refused == Disk.LOOP ? Disk.LOOP_WORDS : Disk.words(refused));
    }

    // ---- streams

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        InputStream in = input(path);
        for (OpenOption option : options) {
            // by its name, so that a program naming no option holds none of their class
            if (option instanceof StandardOpenOption named && named.name().equals("DELETE_ON_CLOSE")) {
                Disk.delete(host(path));
            }
        }
        return in;
    }

    // A stream of a file's bytes, which the members of this class read through.
    private static InputStream input(Path path) throws IOException {
        if (kind(path) == Disk.DIRECTORY) {
            // a JDK opens a directory and refuses to read it
            return new InputStream() {
                public int read() throws IOException {
                    throw new IOException("Is a directory");
                }

                public int read(byte[] b, int off, int len) throws IOException {
                    throw new IOException("Is a directory");
                }
            };
        }
        try {
            return new FileInputStream(host(path));
        }
        catch (FileNotFoundException refused) {
            throw refusal(path, false);
        }
    }

    // A file opened to be deleted when closed is deleted once it is open, as a JDK's is
    // on a Unix host: its name is gone while the stream reads and writes it.
    private static void deletedIfAsked(Path path, OpenOption... options) {
        for (OpenOption option : options) {
            if (option == StandardOpenOption.DELETE_ON_CLOSE) {
                Disk.delete(host(path));
            }
        }
    }

    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        boolean create = options.length == 0;
        boolean createNew = false;
        boolean truncate = options.length == 0;
        boolean append = false;
        for (OpenOption option : options) {
            if (option == StandardOpenOption.READ) {
                throw new IllegalArgumentException("READ not allowed");
            }
            create |= option == StandardOpenOption.CREATE;
            createNew |= option == StandardOpenOption.CREATE_NEW;
            truncate |= option == StandardOpenOption.TRUNCATE_EXISTING;
            append |= option == StandardOpenOption.APPEND;
        }
        if (append && truncate) {
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
        }
        String name = host(path);
        int kind = Disk.kind(name);
        if (createNew && (kind != Disk.NONE || own(path) != Disk.NONE)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (kind == Disk.DIRECTORY) {
            throw new FileSystemException(path.toString(), null, "Is a directory");
        }
        if (kind == Disk.NONE && !create && !createNew) {
            throw new NoSuchFileException(path.toString());
        }
        try {
            if (append || truncate || kind == Disk.NONE) {
                OutputStream out = new FileOutputStream(name, append);
                deletedIfAsked(path, options);
                return out;
            }
            // written from the start over what is there
            RandomAccessFile file = new RandomAccessFile(name, "rw");
            deletedIfAsked(path, options);
            return new OutputStream() {
                public void write(int b) throws IOException {
                    file.write(b);
                }

                public void write(byte[] b, int off, int len) throws IOException {
                    file.write(b, off, len);
                }

                public void close() throws IOException {
                    file.close();
                }
            };
        }
        catch (FileNotFoundException refused) {
            throw refusal(path, true);
        }
    }

    public static BufferedReader newBufferedReader(Path path, Charset cs) throws IOException {
        Objects.requireNonNull(cs);
        return new BufferedReader(new StrictReader(input(path), cs));
    }

    public static BufferedReader newBufferedReader(Path path) throws IOException {
        return newBufferedReader(path, Charset.defaultCharset());
    }

    public static BufferedWriter newBufferedWriter(Path path, Charset cs, OpenOption... options) throws IOException {
        Objects.requireNonNull(cs);
        return new BufferedWriter(new StrictWriter(newOutputStream(path, options), cs));
    }

    public static BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        return newBufferedWriter(path, Charset.defaultCharset(), options);
    }

    // ---- whole files

    public static byte[] readAllBytes(Path path) throws IOException {
        try (InputStream in = input(path)) {
            return in.readAllBytes();
        }
    }

    public static String readString(Path path) throws IOException {
        return readString(path, Charset.defaultCharset());
    }

    public static String readString(Path path, Charset cs) throws IOException {
        Objects.requireNonNull(cs);
        byte[] bytes = readAllBytes(path);
        long bad = cs.malformed(bytes, 0, bytes.length, true);
        if (bad >= 0) {
            throw new MalformedInputException((int) (bad & 7));
        }
        return cs.text(bytes, 0, bytes.length);
    }

    public static List<String> readAllLines(Path path) throws IOException {
        return readAllLines(path, Charset.defaultCharset());
    }

    // The lines as a BufferedReader's readLine gives them.
    public static List<String> readAllLines(Path path, Charset cs) throws IOException {
        Objects.requireNonNull(cs);
        byte[] bytes = readAllBytes(path);
        long bad = cs.malformed(bytes, 0, bytes.length, false);
        if (bad >= 0) {
            throw new MalformedInputException((int) (bad & 7));
        }
        String text = cs.text(bytes, 0, bytes.length);
        List<String> lines = new ArrayList<>();
        int n = text.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                i += c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n' ? 2 : 1;
                start = i;
            }
            else {
                i++;
            }
        }
        if (start < n) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    public static Stream<String> lines(Path path) throws IOException {
        return lines(path, Charset.defaultCharset());
    }

    public static Stream<String> lines(Path path, Charset cs) throws IOException {
        BufferedReader reader = newBufferedReader(path, cs);
        return reader.lines().onClose(() -> {
            try {
                reader.close();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public static Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
        Objects.requireNonNull(bytes);
        try (OutputStream out = newOutputStream(path, options)) {
            out.write(bytes, 0, bytes.length);
        }
        return path;
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, Charset cs, OpenOption... options)
            throws IOException {
        Objects.requireNonNull(lines);
        try (BufferedWriter writer = newBufferedWriter(path, cs, options)) {
            for (CharSequence line : lines) {
                writer.append(line);
                writer.newLine();
            }
        }
        return path;
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, OpenOption... options)
            throws IOException {
        return write(path, lines, Charset.defaultCharset(), options);
    }

    public static Path writeString(Path path, CharSequence csq, OpenOption... options) throws IOException {
        return writeString(path, csq, Charset.defaultCharset(), options);
    }

    // The text is encoded before the file is opened: a refused one leaves no file.
    public static Path writeString(Path path, CharSequence csq, Charset cs, OpenOption... options)
            throws IOException {
        Objects.requireNonNull(path);
        String text = csq.toString();
        long bad = cs.unwritable(text, true);
        if (bad >= 0) {
            throw StrictWriter.refusal(bad);
        }
        byte[] bytes = cs.bytes(text);
        try (OutputStream out = newOutputStream(path, options)) {
            out.write(bytes, 0, bytes.length);
        }
        return path;
    }

    // ---- the tree

    public static boolean exists(Path path, LinkOption... options) {
        return stat(path, options)[Disk.KIND] != Disk.NONE;
    }

    // Only where the host says nothing is there: one that refuses or meets a loop of
    // links cannot say either.
    public static boolean notExists(Path path, LinkOption... options) {
        long[] stat = stat(path, options);
        return stat[Disk.KIND] == Disk.NONE && stat[Disk.WHY] == Disk.NOT_THERE;
    }

    public static boolean isDirectory(Path path, LinkOption... options) {
        return stat(path, options)[Disk.KIND] == Disk.DIRECTORY;
    }

    public static boolean isRegularFile(Path path, LinkOption... options) {
        return stat(path, options)[Disk.KIND] == Disk.FILE;
    }

    public static boolean isSymbolicLink(Path path) {
        return own(path) == Disk.LINK;
    }

    public static boolean isReadable(Path path) {
        return new java.io.File(host(path)).canRead();
    }

    public static boolean isWritable(Path path) {
        return new java.io.File(host(path)).canWrite();
    }

    public static boolean isExecutable(Path path) {
        return new java.io.File(host(path)).canExecute();
    }

    // ---- permission bits: a host without them (a .wasm) has no POSIX view

    // The permissions of the low nine bits of a mode: OWNER_READ is 0400.
    private static Set<PosixFilePermission> permissions(int mode) {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission perm : PosixFilePermission.values()) {
            if ((mode & (0400 >> perm.ordinal())) != 0) {
                perms.add(perm);
            }
        }
        return perms;
    }

    private static int mode(Set<PosixFilePermission> perms) {
        int mode = 0;
        for (PosixFilePermission perm : perms) {
            mode |= 0400 >> perm.ordinal();
        }
        return mode;
    }

    public static Set<PosixFilePermission> getPosixFilePermissions(Path path, LinkOption... options)
            throws IOException {
        if (!Disk.modes()) {
            throw new UnsupportedOperationException();
        }
        long[] stat = stat(path, options);
        if (stat[Disk.KIND] == Disk.NONE) {
            throw unstated(path, stat[Disk.WHY]);
        }
        return permissions((int) stat[Disk.MODE]);
    }

    // The bits given are the file's nine; the others it had are taken away.
    public static Path setPosixFilePermissions(Path path, Set<PosixFilePermission> perms) throws IOException {
        if (!Disk.modes()) {
            throw new UnsupportedOperationException();
        }
        chmod(path, mode(perms));
        return path;
    }

    // Gives what a path names the permission bits (07777): a JDK follows a link even where
    // told not to.
    private static void chmod(Path path, int mode) throws IOException {
        long[] stat = stat(path);
        if (stat[Disk.KIND] == Disk.NONE) {
            throw unstated(path, stat[Disk.WHY]);
        }
        int why = Disk.setMode(host(path), mode);
        if (why != 0) {
            throw unchanged(path, why);
        }
    }

    // The exception a JDK throws where the host would not change a path's bits or owner.
    private static IOException unchanged(Path path, long why) {
        return why == Unix.NOT_PERMITTED ? new FileSystemException(path.toString(), null, "Operation not permitted")
                : unstated(path, why);
    }

    // ---- owners: a host without permission bits (a .wasm) has no owner view either

    // The slots of Disk.unix, and why a change of an owner or bits is refused where the
    // host does not allow it (EPERM). Here, not in Disk, whose static finals every program
    // reaching it carries.
    private static final class Unix {
        static final int UID = 0;

        static final int GID = 1;

        static final int INO = 2;

        static final int DEV = 3;

        static final int RDEV = 4;

        static final int NLINK = 5;

        static final int CTIME = 6;

        static final int UNIX_MODE = 7;

        static final int NOT_PERMITTED = 5;
    }

    public static UserPrincipal getOwner(Path path, LinkOption... options) throws IOException {
        Objects.requireNonNull(path);
        boolean follow = follows(options);
        if (!Disk.modes()) {
            throw new UnsupportedOperationException();
        }
        return owner(path, follow);
    }

    public static Path setOwner(Path path, UserPrincipal owner) throws IOException {
        Objects.requireNonNull(path);
        if (!Disk.modes()) {
            throw new UnsupportedOperationException();
        }
        owned(path, true, owner, false);
        return path;
    }

    // What the host numbers a path's owner and group by, its inode and the rest (Disk.unix's
    // slots).
    private static long[] numbers(Path path, boolean follow) throws IOException {
        long[] unix = Disk.unix(host(path), follow);
        if (unix.length == 1) {
            throw unstated(path, unix[0]);
        }
        return unix;
    }

    // The names of a path's owner and group.
    private static String[] named(Path path, boolean follow) throws IOException {
        String[] names = Disk.owners(host(path), follow);
        if (names == null) {
            numbers(path, follow);
            throw new FileSystemException(path.toString());
        }
        return names;
    }

    private static UserPrincipal owner(Path path, boolean follow) throws IOException {
        long[] unix = numbers(path, follow);
        return new Owner((int) unix[Unix.UID], named(path, follow)[0]);
    }

    // Gives a path an owner, or a group: refused in a JDK's words and order, a principal
    // of another class before a group given as an owner, before the path.
    private static void owned(Path path, boolean follow, UserPrincipal owner, boolean group) throws IOException {
        if (owner == null) {
            throw new NullPointerException("'owner' is null");
        }
        if (!(owner instanceof Owner)) {
            throw new ProviderMismatchException();
        }
        if (!group && owner instanceof GroupPrincipal) {
            throw new IOException("'owner' parameter can't be a group");
        }
        numbered(path, follow, group, ((Owner) owner).id);
    }

    private static void numbered(Path path, boolean follow, boolean group, int id) throws IOException {
        int why = Disk.setOwner(host(path), follow, group ? -1 : id, group ? id : -1);
        if (why != 0) {
            throw unchanged(path, why);
        }
    }

    // An owner or a group as the host numbers and names it: equal to one of the same kind
    // and number, which is its hash (a JDK's, measured).
    private static class Owner implements UserPrincipal {
        final int id;

        private final String name;

        Owner(int id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean group() {
            return false;
        }

        public String getName() {
            return name;
        }

        public String toString() {
            return name;
        }

        public int hashCode() {
            return id;
        }

        public boolean equals(Object other) {
            return other instanceof Owner that && that.id == id && that.group() == group();
        }
    }

    private static final class Group extends Owner implements GroupPrincipal {
        Group(int id, String name) {
            super(id, name);
        }

        boolean group() {
            return true;
        }
    }

    public static boolean isHidden(Path path) throws IOException {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    public static boolean isSameFile(Path path, Path path2) throws IOException {
        if (path.equals(path2)) {
            return true;
        }
        return path.toRealPath().equals(path2.toRealPath());
    }

    public static long size(Path path) throws IOException {
        return attributes(path).size();
    }

    // The permission bits the attributes a file is to be made with give it, the last
    // one's, or -1 for none; any other attribute, and that one where the host has no
    // permission bits, is refused.
    @SuppressWarnings("unchecked")
    private static int made(FileAttribute<?>... attrs) {
        int mode = -1;
        for (FileAttribute<?> attr : attrs) {
            if (!attr.name().equals("posix:permissions") || !Disk.modes()) {
                throw new UnsupportedOperationException("'" + attr.name() + "' not supported as initial attribute");
            }
            Object value = attr.value();
            mode = mode((Set<PosixFilePermission>) value);
        }
        return mode;
    }

    private static boolean empty(Path dir) {
        String[] names = Disk.names(host(dir));
        return names != null && names.length == 0;
    }

    // The exception a JDK throws where the host's stat of a path could not say what it
    // is.
    private static IOException unstated(Path path, long why) {
        return LocalPath.unstated(path.toString(), why);
    }

    // What the host's stat says of a path, its links followed unless an option says
    // not: its kind, its size and its three times to the nanosecond.
    private static BasicFileAttributes attributes(Path path, LinkOption... options) throws IOException {
        long[] stat = stat(path, options);
        if (stat[Disk.KIND] == Disk.NONE) {
            throw unstated(path, stat[Disk.WHY]);
        }
        return new Described(stat);
    }

    // What a stat said, as attributes. A file's key the subset does not have.
    private static class Described implements BasicFileAttributes {
        private final long[] stat;

        Described(long[] stat) {
            this.stat = stat;
        }

        public FileTime lastModifiedTime() {
            return FileTime.from(stat[Disk.MODIFIED], TimeUnit.NANOSECONDS);
        }

        public FileTime lastAccessTime() {
            return FileTime.from(stat[Disk.ACCESSED], TimeUnit.NANOSECONDS);
        }

        public FileTime creationTime() {
            return FileTime.from(stat[Disk.MADE], TimeUnit.NANOSECONDS);
        }

        public boolean isRegularFile() {
            return stat[Disk.KIND] == Disk.FILE;
        }

        public boolean isDirectory() {
            return stat[Disk.KIND] == Disk.DIRECTORY;
        }

        public boolean isSymbolicLink() {
            return stat[Disk.KIND] == Disk.LINK;
        }

        public boolean isOther() {
            return stat[Disk.KIND] == Disk.OTHER;
        }

        public long size() {
            return stat[Disk.SIZE];
        }

        public Object fileKey() {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        Objects.requireNonNull(type);
        if (type == PosixFileAttributes.class && Disk.modes()) {
            return (A) posix(path, follows(options));
        }
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException();
        }
        return (A) attributes(path, options);
    }

    private static PosixFileAttributes posix(Path path, boolean follow) throws IOException {
        long[] stat = Disk.stat(host(path), follow);
        if (stat[Disk.KIND] == Disk.NONE) {
            throw unstated(path, stat[Disk.WHY]);
        }
        return new Posix(stat, numbers(path, follow), named(path, follow));
    }

    // What a stat said with the owner, the group and the permission bits.
    private static final class Posix extends Described implements PosixFileAttributes {
        private final long[] unix;

        private final String[] names;

        Posix(long[] stat, long[] unix, String[] names) {
            super(stat);
            this.unix = unix;
            this.names = names;
        }

        public UserPrincipal owner() {
            return new Owner((int) unix[Unix.UID], names[0]);
        }

        public GroupPrincipal group() {
            return new Group((int) unix[Unix.GID], names[1]);
        }

        public Set<PosixFilePermission> permissions() {
            return Files.permissions((int) unix[Unix.UNIX_MODE]);
        }
    }

    public static FileTime getLastModifiedTime(Path path, LinkOption... options) throws IOException {
        return attributes(path, options).lastModifiedTime();
    }

    // The basic view of any path, the posix and the owner views where the host has
    // permission bits: what one says and writes is asked when it is used. A JDK on a Unix
    // host has more, which the subset does not.
    @SuppressWarnings("unchecked")
    public static <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
            LinkOption... options) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(type);
        boolean follow = follows(options);
        if (type == BasicFileAttributeView.class) {
            return (V) new View(path, follow);
        }
        if (!Disk.modes()) {
            return null;
        }
        return type == PosixFileAttributeView.class ? (V) new PosixView(path, follow)
                : type == FileOwnerAttributeView.class ? (V) new OwnerView(path, follow) : null;
    }

    private static final class OwnerView implements FileOwnerAttributeView {
        private final Path path;

        private final boolean follow;

        OwnerView(Path path, boolean follow) {
            this.path = path;
            this.follow = follow;
        }

        public String name() {
            return "owner";
        }

        public UserPrincipal getOwner() throws IOException {
            return owner(path, follow);
        }

        public void setOwner(UserPrincipal owner) throws IOException {
            owned(path, follow, owner, false);
        }
    }

    private static final class PosixView extends View implements PosixFileAttributeView {
        PosixView(Path path, boolean follow) {
            super(path, follow);
        }

        public String name() {
            return "posix";
        }

        public PosixFileAttributes readAttributes() throws IOException {
            return posix(path, follow);
        }

        public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
            chmod(path, mode(perms));
        }

        public void setGroup(GroupPrincipal group) throws IOException {
            owned(path, follow, group, true);
        }

        public UserPrincipal getOwner() throws IOException {
            return owner(path, follow);
        }

        public void setOwner(UserPrincipal owner) throws IOException {
            owned(path, follow, owner, false);
        }
    }

    private static class View implements BasicFileAttributeView {
        final Path path;

        final boolean follow;

        View(Path path, boolean follow) {
            this.path = path;
            this.follow = follow;
        }

        public String name() {
            return "basic";
        }

        public BasicFileAttributes readAttributes() throws IOException {
            return follow ? attributes(path) : attributes(path, LinkOption.NOFOLLOW_LINKS);
        }

        // No time given asks nothing of the path; the time of making is not written (a
        // JDK on a Unix host writes none).
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime)
                throws IOException {
            if (lastModifiedTime == null && lastAccessTime == null) {
                return;
            }
            given(path, follow);
            written(path, follow, lastModifiedTime == null ? null : lastModifiedTime.to(TimeUnit.NANOSECONDS),
                    lastAccessTime == null ? null : lastAccessTime.to(TimeUnit.NANOSECONDS));
        }
    }

    // The names of the basic view, in the order a JDK's map is filled in (two pairs of
    // them share a bucket, which keeps the first put first).
    private static final String[] BASIC = { "lastAccessTime", "lastModifiedTime", "size", "creationTime",
            "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey" };

    // The names of the posix view (the basic view's and three) and the unix view's (eight
    // more), in the order a JDK fills its map.
    private static String[] unixNames() {
        return new String[] { "lastAccessTime", "lastModifiedTime", "size", "creationTime", "isRegularFile",
                "isDirectory", "isSymbolicLink", "isOther", "fileKey", "permissions", "owner", "group", "mode", "ino",
                "dev", "rdev", "nlink", "uid", "gid", "ctime" };
    }

    // The view a text of attributes names, [view:]name,name..: the basic view's where it
    // names none, refused in a JDK's words where the host has no such view.
    private static String view(String attributes) {
        int colon = attributes.indexOf(':');
        if (colon == 0) {
            throw new IllegalArgumentException(attributes);
        }
        String view = colon < 0 ? "basic" : attributes.substring(0, colon);
        if (!view.equals("basic") && (!Disk.modes()
                || !(view.equals("posix") || view.equals("unix") || view.equals("owner")))) {
            throw new UnsupportedOperationException("View '" + view + "' not available");
        }
        return view;
    }

    // The names a text of attributes asks of its view, or * for them all, refused in a
    // JDK's words and order: the view first, the owner view's names with it.
    private static Set<String> asked(String attributes) {
        String view = view(attributes);
        String[] all = view.equals("basic") ? BASIC : unixNames();
        int known = view.equals("basic") ? BASIC.length : view.equals("posix") ? 12 : 20;
        Set<String> names = new HashSet<>();
        for (String name : attributes.substring(attributes.indexOf(':') + 1).split(",")) {
            if (view.equals("owner")) {
                if (!name.equals("*") && !name.equals("owner")) {
                    throw new IllegalArgumentException("'owner:" + name + "' not recognized");
                }
                names.add("owner");
            }
            else if (name.equals("*")) {
                for (int i = 0; i < known; i++) {
                    names.add(all[i]);
                }
            }
            else if (index(all, name) < known) {
                names.add(name);
            }
            else {
                throw new IllegalArgumentException("'" + name + "' not recognized");
            }
        }
        return names;
    }

    private static int index(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return names.length;
    }

    private static Object value(BasicFileAttributes attrs, String name) {
        return switch (name) {
            case "lastAccessTime" -> attrs.lastAccessTime();
            case "lastModifiedTime" -> attrs.lastModifiedTime();
            case "size" -> attrs.size();
            case "creationTime" -> attrs.creationTime();
            case "isRegularFile" -> attrs.isRegularFile();
            case "isDirectory" -> attrs.isDirectory();
            case "isSymbolicLink" -> attrs.isSymbolicLink();
            case "isOther" -> attrs.isOther();
            default -> attrs.fileKey();
        };
    }

    // The attributes of a view by name: the basic view's, and where the host has
    // permission bits the posix, unix and owner views'. The owner view's map is a JDK's
    // that may be changed.
    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        Set<String> names = asked(attributes);
        BasicFileAttributes attrs = attributes(path, options);
        Map<String, Object> map = new HashMap<>();
        if (attributes.indexOf(':') < 0 || attributes.startsWith("basic:")) {
            for (String name : BASIC) {
                if (names.contains(name)) {
                    map.put(name, value(attrs, name));
                }
            }
            return Collections.unmodifiableMap(map);
        }
        boolean follow = follows(options);
        long[] unix = numbers(path, follow);
        String[] owners = names.contains("owner") || names.contains("group") ? named(path, follow) : null;
        for (String name : unixNames()) {
            if (names.contains(name)) {
                map.put(name, unixValue(attrs, unix, owners, name));
            }
        }
        return attributes.startsWith("owner:") ? map : Collections.unmodifiableMap(map);
    }

    private static Object unixValue(BasicFileAttributes attrs, long[] unix, String[] owners, String name) {
        return switch (name) {
            case "permissions" -> permissions((int) unix[Unix.UNIX_MODE]);
            case "owner" -> new Owner((int) unix[Unix.UID], owners[0]);
            case "group" -> new Group((int) unix[Unix.GID], owners[1]);
            case "mode" -> Integer.valueOf((int) unix[Unix.UNIX_MODE]);
            case "ino" -> Long.valueOf(unix[Unix.INO]);
            case "dev" -> Long.valueOf(unix[Unix.DEV]);
            case "rdev" -> Long.valueOf(unix[Unix.RDEV]);
            case "nlink" -> Integer.valueOf((int) unix[Unix.NLINK]);
            case "uid" -> Integer.valueOf((int) unix[Unix.UID]);
            case "gid" -> Integer.valueOf((int) unix[Unix.GID]);
            case "ctime" -> FileTime.from(unix[Unix.CTIME], TimeUnit.NANOSECONDS);
            default -> value(attrs, name);
        };
    }

    // One attribute: a text naming several is refused before it is read.
    public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        if (attribute.indexOf('*') >= 0 || attribute.indexOf(',') >= 0) {
            throw new IllegalArgumentException(attribute);
        }
        Map<String, Object> map = readAttributes(path, attribute, options);
        return map.get(attribute.substring(attribute.indexOf(':') + 1));
    }

    // A time of the basic view by name, through the posix and unix views too; the time of
    // making is not written (a JDK on a Unix host writes none). The posix and unix views
    // give the permission bits, which follow a link as chmod does, and the owner and the
    // group, as the owner view gives the owner; the unix view the mode and the numbers of
    // the owner and the group.
    @SuppressWarnings("unchecked")
    public static Path setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        String view = view(attribute);
        String name = attribute.substring(attribute.indexOf(':') + 1);
        boolean posix = view.equals("posix") || view.equals("unix");
        boolean unix = view.equals("unix");
        boolean timed = name.equals("lastModifiedTime") || name.equals("lastAccessTime")
                || name.equals("creationTime");
        if (!timed || view.equals("owner")) {
            if (posix && name.equals("permissions")) {
                chmod(path, mode((Set<PosixFilePermission>) value));
            }
            else if ((posix || view.equals("owner")) && name.equals("owner")) {
                owned(path, follows(options), (UserPrincipal) value, false);
            }
            else if (posix && name.equals("group")) {
                owned(path, follows(options), (GroupPrincipal) value, true);
            }
            else if (unix && name.equals("mode")) {
                chmod(path, ((Integer) value).intValue() & 07777);
            }
            else if (unix && (name.equals("uid") || name.equals("gid"))) {
                numbered(path, follows(options), name.equals("gid"), ((Integer) value).intValue());
            }
            else {
                throw new IllegalArgumentException("'" + view + ":" + name + "' not recognized");
            }
            return path;
        }
        FileTime time = (FileTime) value;
        boolean follow = follows(options);
        given(path, follow);
        if (time != null && !name.equals("creationTime")) {
            Long nanos = time.to(TimeUnit.NANOSECONDS);
            written(path, follow, name.equals("lastModifiedTime") ? nanos : null,
                    name.equals("lastAccessTime") ? nanos : null);
        }
        return path;
    }

    public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
        Objects.requireNonNull(time);
        given(path, true);
        written(path, true, time.to(TimeUnit.NANOSECONDS), null);
        return path;
    }

    // A JDK opens a file to give it its times, so one the program may not read refuses
    // them, as does a directory it may not list; a link not followed is not opened.
    private static void given(Path path, boolean follow) throws IOException {
        String name = host(path);
        int kind = (int) Disk.stat(name, follow)[Disk.KIND];
        if (kind == Disk.NONE) {
            throw refusal(path, false);
        }
        if (kind == Disk.DIRECTORY) {
            if (Disk.names(name) == null) {
                throw new AccessDeniedException(path.toString());
            }
        }
        else if (kind != Disk.LINK) {
            Object file = Disk.open(name, Disk.READ);
            if (file == null) {
                throw refusal(path, false);
            }
            Disk.close(file);
        }
    }

    private static void written(Path path, boolean follow, Long modified, Long accessed) throws IOException {
        int why = Disk.setTimes(host(path), follow, modified, accessed);
        if (why != 0) {
            throw unstated(path, why);
        }
    }

    public static Path createFile(Path path, FileAttribute<?>... attrs) throws IOException {
        return createFile(path, made(attrs));
    }

    // A file made with the permission bits less the umask, or the host's (-1).
    private static Path createFile(Path path, int mode) throws IOException {
        String name = host(path);
        if (own(path) != Disk.NONE) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (mode >= 0) {
            if (Disk.make(name, mode, false) != 0) {
                throw refusal(path, true);
            }
            return path;
        }
        Object made = Disk.open(name, Disk.APPEND);
        if (made == null) {
            throw refusal(path, true);
        }
        Disk.close(made);
        return path;
    }

    // A link holding the target as its path spells it, with no attribute (a JDK on a
    // Unix host takes none); an empty target makes none. A WASI host makes no link whose
    // target is absolute: AccessDeniedException.
    public static Path createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        String name = host(link);
        String held = target.toString();
        for (FileAttribute<?> attr : attrs) {
            Objects.requireNonNull(attr);
        }
        if (attrs.length > 0) {
            throw new UnsupportedOperationException("Initial file attributes not supported when creating symbolic link");
        }
        if (held.isEmpty()) {
            throw new NoSuchFileException(link.toString());
        }
        if (own(link) != Disk.NONE) {
            throw new FileAlreadyExistsException(link.toString());
        }
        if (!Disk.link(held, name)) {
            throw refusal(link, true);
        }
        return link;
    }

    // What a link holds, as a path. A WASI host reads no link whose target is absolute:
    // AccessDeniedException.
    public static Path readSymbolicLink(Path link) throws IOException {
        String name = host(link);
        String held = Disk.readLink(name);
        if (held != null) {
            return Path.of(held);
        }
        int kind = own(link);
        if (kind == Disk.LINK) {
            throw new AccessDeniedException(link.toString());
        }
        if (kind != Disk.NONE) {
            throw new NotLinkException(link.toString());
        }
        throw refusal(link, false);
    }

    public static Path createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        return createDirectory(dir, made(attrs));
    }

    private static Path createDirectory(Path dir, int mode) throws IOException {
        String name = host(dir);
        if (own(dir) != Disk.NONE) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!madeDirectory(name, mode)) {
            throw refusal(dir, true);
        }
        return dir;
    }

    // Makes one directory in one that is there, with the permission bits less the umask
    // or the host's (-1): whether it is one now.
    private static boolean madeDirectory(String name, int mode) {
        String above = Disk.above(name);
        if (above != null && Disk.kind(above) != Disk.DIRECTORY) {
            return false;
        }
        boolean made = mode >= 0 ? Disk.make(name, mode, true) == 0 : Disk.makeDirectories(name);
        return made && Disk.kind(name) == Disk.DIRECTORY;
    }

    // Each directory missing is made, the nearest to the root first; one refused is named
    // in the path made absolute.
    public static Path createDirectories(Path dir, FileAttribute<?>... attrs) throws IOException {
        int mode = made(attrs);
        int kind = kind(dir);
        if (kind == Disk.DIRECTORY) {
            return dir;
        }
        // something not a directory is there, or a link that leads nowhere
        if (kind != Disk.NONE || own(dir) != Disk.NONE) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        Deque<Path> missing = new ArrayDeque<>();
        for (Path at = dir; at != null && kind(at) == Disk.NONE; at = at.getParent()) {
            missing.push(at);
        }
        for (Path at : missing) {
            // one made meanwhile by another is there as it should be
            if (!madeDirectory(host(at), mode) && kind(at) != Disk.DIRECTORY) {
                throw refusal(at.toAbsolutePath(), true);
            }
        }
        return dir;
    }

    // A name for a temporary file or directory: the prefix, a number no file is likely to
    // have and the suffix, refused where either holds a slash.
    private static Path temporary(Path dir, String prefix, String suffix) {
        String start = prefix == null ? "" : prefix;
        if (start.indexOf('/') >= 0 || (suffix != null && suffix.indexOf('/') >= 0)) {
            throw new IllegalArgumentException("Invalid prefix or suffix");
        }
        return dir.resolve(Disk.unlikely(start, suffix == null ? "" : suffix));
    }

    // A file made new in a directory, /tmp where none is given, named by the prefix, a
    // number no file is likely to have and the suffix (.tmp where none is given).
    // Where the host has permission bits and none are given, only the owner may read and
    // write it.
    public static Path createTempFile(Path dir, String prefix, String suffix, FileAttribute<?>... attrs)
            throws IOException {
        Objects.requireNonNull(dir);
        int mode = made(attrs);
        if (mode < 0 && Disk.modes()) {
            mode = 0600;
        }
        while (true) {
            Path made = temporary(dir, prefix, suffix == null ? ".tmp" : suffix);
            if (kind(made) == Disk.NONE) {
                return createFile(made, mode);
            }
        }
    }

    public static Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
        return createTempFile(Path.of(Disk.TEMPORARY), prefix, suffix, attrs);
    }

    // Where the host has permission bits and none are given, only the owner may use it.
    public static Path createTempDirectory(Path dir, String prefix, FileAttribute<?>... attrs) throws IOException {
        Objects.requireNonNull(dir);
        int mode = made(attrs);
        if (mode < 0 && Disk.modes()) {
            mode = 0700;
        }
        while (true) {
            Path made = temporary(dir, prefix, null);
            if (kind(made) == Disk.NONE) {
                return createDirectory(made, mode);
            }
        }
    }

    public static Path createTempDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
        return createTempDirectory(Path.of(Disk.TEMPORARY), prefix, attrs);
    }

    // A link is deleted, not what it leads to.
    public static void delete(Path path) throws IOException {
        String name = host(path);
        int kind = own(path);
        if (kind == Disk.NONE) {
            throw new NoSuchFileException(path.toString());
        }
        if (path.toString().isEmpty()) {
            throw new FileSystemException("", null, "Invalid argument");
        }
        if (!Disk.delete(name)) {
            String[] names = kind == Disk.DIRECTORY ? Disk.names(name) : null;
            if (names != null && names.length > 0) {
                throw new DirectoryNotEmptyException(path.toString());
            }
            throw new AccessDeniedException(path.toString());
        }
    }

    public static boolean deleteIfExists(Path path) throws IOException {
        if (own(path) == Disk.NONE) {
            return false;
        }
        delete(path);
        return true;
    }

    private static boolean replaces(CopyOption... options) {
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                return true;
            }
        }
        return false;
    }

    // What a copy or a move takes a path to be: its real path, or where a link in its last
    // name is not followed, the link's own place.
    private static Path identity(Path path, boolean follow) throws IOException {
        if (follow || own(path) != Disk.LINK) {
            return path.toRealPath();
        }
        Path parent = path.toAbsolutePath().getParent();
        Path name = path.getFileName();
        return parent == null || name == null ? path.toRealPath(LinkOption.NOFOLLOW_LINKS)
                : parent.toRealPath().resolve(name);
    }

    // Makes way for a copy or a move: a target there, a link even where it leads nowhere,
    // is refused, or deleted when the options replace it.
    private static void cleared(Path target, boolean replace) throws IOException {
        if (own(target) != Disk.NONE) {
            if (!replace) {
                throw new FileAlreadyExistsException(target.toString());
            }
            delete(target);
        }
    }

    // A link is copied as what it leads to; with NOFOLLOW_LINKS it is made again, holding
    // what the source holds, and none of its times (a JDK's). The copy is made with the
    // source's permission bits less the umask; COPY_ATTRIBUTES gives it the source's bits
    // and its times of last writing and last access.
    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean attributes = false;
        boolean follow = true;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.ATOMIC_MOVE) {
                throw new UnsupportedOperationException("Unsupported copy option: ATOMIC_MOVE");
            }
            attributes |= option == StandardCopyOption.COPY_ATTRIBUTES;
            follow &= option != LinkOption.NOFOLLOW_LINKS;
        }
        long[] stat = Disk.stat(host(source), follow);
        int kind = (int) stat[Disk.KIND];
        if (kind == Disk.NONE) {
            throw unstated(source, stat[Disk.WHY]);
        }
        String held = kind == Disk.LINK ? Disk.readLink(host(source)) : null;
        if (kind == Disk.LINK && held == null) {
            throw new AccessDeniedException(source.toString());
        }
        if (own(target) != Disk.NONE && identity(source, follow).equals(identity(target, false))) {
            return target;
        }
        cleared(target, replaces(options));
        if (held != null) {
            if (!Disk.link(held, host(target))) {
                throw refusal(target, true);
            }
            return target;
        }
        int mode = (int) stat[Disk.MODE];
        if (kind == Disk.DIRECTORY) {
            // a directory's entries are not copied
            createDirectory(target, mode < 0 ? -1 : mode & 0777);
        }
        else if (mode < 0) {
            try (InputStream in = input(source); OutputStream out = newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
        }
        else {
            // the owner may write the copy while it is written
            try (InputStream in = input(source)) {
                createFile(target, mode & 0777 | 0200);
                try (OutputStream out = newOutputStream(target, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }
            }
            if (!attributes && (mode & 0200) == 0) {
                Disk.setMode(host(target), (int) Disk.stat(host(target), true)[Disk.MODE] & ~0200);
            }
        }
        if (attributes) {
            written(target, true, stat[Disk.MODIFIED], stat[Disk.ACCESSED]);
            if (mode >= 0) {
                Disk.setMode(host(target), mode);
            }
        }
        return target;
    }

    public static long copy(InputStream in, Path target, CopyOption... options) throws IOException {
        Objects.requireNonNull(in);
        cleared(target, replaces(options));
        try (OutputStream out = newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            return in.transferTo(out);
        }
    }

    public static long copy(Path source, OutputStream out) throws IOException {
        Objects.requireNonNull(out);
        try (InputStream in = input(source)) {
            return in.transferTo(out);
        }
    }

    public static Path move(Path source, Path target, CopyOption... options) throws IOException {
        boolean atomic = false;
        for (CopyOption option : options) {
            atomic |= option == StandardCopyOption.ATOMIC_MOVE;
        }
        // a link is moved, not what it leads to
        if (own(source) == Disk.NONE) {
            throw atomic ? new NoSuchFileException(source.toString(), target.toString(), null)
                    : new NoSuchFileException(source.toString());
        }
        int there = own(target);
        if (there != Disk.NONE && identity(source, false).equals(identity(target, false))) {
            return target;
        }
        if (atomic) {
            // the host's rename, whatever the other options: a file or an empty
            // directory there is replaced by what it can be replaced by
            if (there != Disk.NONE) {
                int moved = own(source);
                String reason = moved != Disk.DIRECTORY && there == Disk.DIRECTORY ? "Is a directory"
                        : moved == Disk.DIRECTORY && there != Disk.DIRECTORY ? "Not a directory"
                                : there == Disk.DIRECTORY && !empty(target) ? "Directory not empty" : null;
                if (reason != null) {
                    throw new FileSystemException(source.toString(), target.toString(), reason);
                }
            }
        }
        else {
            cleared(target, replaces(options));
        }
        if (!Disk.rename(host(source), host(target))) {
            // a refusal names both paths
            IOException refused = refusal(target, true);
            String from = source.toString();
            String to = target.toString();
            throw refused instanceof NoSuchFileException ? new NoSuchFileException(from, to, null)
                    : refused instanceof AccessDeniedException ? new AccessDeniedException(from, to, null)
                            : new FileSystemException(from, to, ((FileSystemException) refused).getReason());
        }
        return target;
    }

    public static long mismatch(Path path, Path path2) throws IOException {
        byte[] one = readAllBytes(path);
        byte[] two = path.equals(path2) ? one : readAllBytes(path2);
        int n = Math.min(one.length, two.length);
        for (int i = 0; i < n; i++) {
            if (one[i] != two[i]) {
                return i;
            }
        }
        return one.length == two.length ? -1 : n;
    }

    // ---- listing

    // The paths in a directory, each the directory's resolved with a name, in the host's
    // order.
    private static List<Path> entries(Path dir) throws IOException {
        String[] names = Disk.names(host(dir));
        if (names == null) {
            long[] stat = Disk.stat(host(dir), true);
            if (stat[Disk.KIND] == Disk.NONE) {
                throw unstated(dir, stat[Disk.WHY]);
            }
            throw stat[Disk.KIND] == Disk.DIRECTORY ? new AccessDeniedException(dir.toString())
                    : new NotDirectoryException(dir.toString());
        }
        List<Path> paths = new ArrayList<>(names.length);
        for (String name : names) {
            paths.add(dir.resolve(name));
        }
        return paths;
    }

    public static Stream<Path> list(Path dir) throws IOException {
        return ObjectFlow.over(entries(dir).iterator());
    }

    // The entries of a directory, listed when the stream is opened; the filter is asked
    // of each as the iterator reaches it, what it throws a DirectoryIteratorException.
    public static DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        Objects.requireNonNull(filter);
        List<Path> listed = entries(dir);
        return new DirectoryStream<Path>() {
            private boolean asked;

            public Iterator<Path> iterator() {
                if (asked) {
                    throw new IllegalStateException("Iterator already obtained");
                }
                asked = true;
                Iterator<Path> each = listed.iterator();
                return new Iterator<Path>() {
                    private Path next;

                    public boolean hasNext() {
                        while (next == null && each.hasNext()) {
                            Path entry = each.next();
                            try {
                                if (filter.accept(entry)) {
                                    next = entry;
                                }
                            }
                            catch (IOException e) {
                                throw new DirectoryIteratorException(e);
                            }
                        }
                        return next != null;
                    }

                    public Path next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        Path entry = next;
                        next = null;
                        return entry;
                    }
                };
            }

            public void close() {
            }
        };
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
        return newDirectoryStream(dir, entry -> true);
    }

    // The entries whose names the glob matches (FileSystem.getPathMatcher's).
    public static DirectoryStream<Path> newDirectoryStream(Path dir, String glob) throws IOException {
        if (glob.equals("*")) {
            return newDirectoryStream(dir);
        }
        Glob matcher = Glob.of(glob);
        return newDirectoryStream(dir, entry -> matcher.matches(entry.getFileName().toString()));
    }

    // Depth first, a directory's entries in the host's order, listed before the visitor is
    // told of the directory: a directory that cannot be listed is a file that failed. A
    // link is a file the walk does not go into, but with FOLLOW_LINKS, where it is what it
    // leads to (itself where it leads nowhere) and a directory the walk is already in is
    // a FileSystemLoopException.
    public static Path walkFileTree(Path start, Set<FileVisitOption> options, int maxDepth,
            FileVisitor<? super Path> visitor) throws IOException {
        Objects.requireNonNull(visitor);
        if (maxDepth < 0) {
            throw new IllegalArgumentException("'maxDepth' is negative");
        }
        boolean follow = options.contains(FileVisitOption.FOLLOW_LINKS);
        visit(Objects.requireNonNull(start), 0, maxDepth, visitor, follow ? new ArrayList<>() : null);
        return start;
    }

    public static Path walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
        return walkFileTree(start, Set.of(), Integer.MAX_VALUE, visitor);
    }

    // What the walk does after a file: go on, leave the directory it is in
    // (SKIP_SIBLINGS) or stop. Where it follows links, the real paths of the directories
    // it is in.
    private static FileVisitResult visit(Path file, int depth, int maxDepth, FileVisitor<? super Path> visitor,
            List<Path> within) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = walked(file, within != null);
        }
        catch (IOException failed) {
            return Objects.requireNonNull(visitor.visitFileFailed(file, failed));
        }
        if (depth >= maxDepth || !attrs.isDirectory()) {
            return Objects.requireNonNull(visitor.visitFile(file, attrs));
        }
        Path real = null;
        if (within != null) {
            try {
                real = looped(file, within);
            }
            catch (IOException failed) {
                return Objects.requireNonNull(visitor.visitFileFailed(file, failed));
            }
            if (real == null) {
                return Objects
                    .requireNonNull(visitor.visitFileFailed(file, new FileSystemLoopException(file.toString())));
            }
        }
        List<Path> listed;
        try {
            listed = entries(file);
        }
        catch (IOException failed) {
            return Objects.requireNonNull(visitor.visitFileFailed(file, failed));
        }
        FileVisitResult before = Objects.requireNonNull(visitor.preVisitDirectory(file, attrs));
        if (before != FileVisitResult.CONTINUE) {
            return before == FileVisitResult.SKIP_SUBTREE ? FileVisitResult.CONTINUE : before;
        }
        if (within != null) {
            within.add(real);
        }
        try {
            for (Path entry : listed) {
                FileVisitResult after = visit(entry, depth + 1, maxDepth, visitor, within);
                if (after == FileVisitResult.TERMINATE) {
                    return after;
                }
                if (after == FileVisitResult.SKIP_SIBLINGS) {
                    break;
                }
            }
        }
        finally {
            if (within != null) {
                within.remove(within.size() - 1);
            }
        }
        return Objects.requireNonNull(visitor.postVisitDirectory(file, null));
    }

    // The attributes a walk sees of a file: a link's own, or with FOLLOW_LINKS what it
    // leads to, its own where it leads nowhere.
    private static BasicFileAttributes walked(Path file, boolean follow) throws IOException {
        if (follow) {
            long[] stat = Disk.stat(host(file), true);
            if (stat[Disk.KIND] != Disk.NONE) {
                return new Described(stat);
            }
            long[] own = Disk.stat(host(file), false);
            if (own[Disk.KIND] == Disk.LINK) {
                return new Described(own);
            }
            throw unstated(file, stat[Disk.WHY]);
        }
        return attributes(file, LinkOption.NOFOLLOW_LINKS);
    }

    // The real path of a directory a walk following links goes into, or null where it is
    // one the walk is already in.
    private static Path looped(Path dir, List<Path> within) throws IOException {
        Path real = dir.toRealPath();
        return within.contains(real) ? null : real;
    }

    private static boolean follows(FileVisitOption... options) {
        for (FileVisitOption option : options) {
            if (Objects.requireNonNull(option) == FileVisitOption.FOLLOW_LINKS) {
                return true;
            }
        }
        return false;
    }

    // The paths of a walk whose attributes the matcher takes.
    public static Stream<Path> find(Path start, int maxDepth, BiPredicate<Path, BasicFileAttributes> matcher,
            FileVisitOption... options) throws IOException {
        Objects.requireNonNull(matcher);
        boolean follow = follows(options);
        return walk(start, maxDepth, options).filter(path -> {
            try {
                return matcher.test(path, walked(path, follow));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public static Stream<Path> walk(Path start, FileVisitOption... options) throws IOException {
        return walk(start, Integer.MAX_VALUE, options);
    }

    // Depth first, a directory before what it holds, each directory's entries in the
    // host's order and listed when the walk reaches it. A link is not gone into but with
    // FOLLOW_LINKS, where a directory the walk is already in is a FileSystemLoopException
    // in place of the path.
    public static Stream<Path> walk(Path start, int maxDepth, FileVisitOption... options) throws IOException {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("'maxDepth' is negative");
        }
        boolean follow = follows(options);
        walked(start, follow);
        Deque<Iterator<Path>> open = new ArrayDeque<>();
        List<Path> within = new ArrayList<>();
        return ObjectFlow.over(new Iterator<Path>() {
            private Path next = start;

            // the real path of the next one where it is a directory to be gone into
            private Path real;

            public boolean hasNext() {
                while (next == null && !open.isEmpty()) {
                    Iterator<Path> top = open.peek();
                    if (top.hasNext()) {
                        next = top.next();
                        real = null;
                        if (follow && open.size() < maxDepth && kind(next) == Disk.DIRECTORY) {
                            try {
                                real = looped(next, within.subList(0, open.size()));
                            }
                            catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            if (real == null) {
                                Path loop = next;
                                next = null;
                                throw new UncheckedIOException(new FileSystemLoopException(loop.toString()));
                            }
                        }
                    }
                    else {
                        open.pop();
                    }
                }
                return next != null;
            }

            public Path next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Path walked = next;
                next = null;
                if (open.size() < maxDepth && (follow ? kind(walked) : own(walked)) == Disk.DIRECTORY) {
                    try {
                        if (follow) {
                            while (within.size() > open.size()) {
                                within.remove(within.size() - 1);
                            }
                            within.add(real != null ? real : walked.toRealPath());
                        }
                        open.push(entries(walked).iterator());
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return walked;
            }
        });
    }
}

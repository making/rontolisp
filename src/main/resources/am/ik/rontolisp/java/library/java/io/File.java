// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import rontolisp.internal.Disk;
import rontolisp.internal.LocalPath;

// A path and the questions a program asks the host's file system of it. The path is the
// one given with each run of slashes one slash and no slash at its end but the root's:
// what a JDK answers on a Unix host, read off its answers (getParent, getName, the paths
// of a parent and a child, an empty parent meaning the root). Relative paths are resolved
// by the host against the working directory, which getAbsolutePath names where the host
// has one to name, and which the empty path is to what is asked of the host (it is not
// made, deleted or renamed). A path holding a NUL names nothing.
public class File implements Serializable, Comparable<File> {

    public static final char separatorChar = '/';

    public static final String separator = "/";

    public static final char pathSeparatorChar = ':';

    public static final String pathSeparator = ":";

    private final String path;

    public File(String pathname) {
        this.path = Disk.normal(Objects.requireNonNull(pathname));
    }

    public File(String parent, String child) {
        String named = Disk.normal(Objects.requireNonNull(child));
        this.path = parent == null ? named : parent.isEmpty() ? resolved("/", named) : resolved(Disk.normal(parent), named);
    }

    public File(File parent, String child) {
        String named = Disk.normal(Objects.requireNonNull(child));
        this.path = parent == null ? named : parent.path.isEmpty() ? resolved("/", named) : resolved(parent.path, named);
    }

    // The path of a file: URI, its escapes read as UTF-8; what it refuses is a JDK's, in
    // its order.
    public File(URI uri) {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URI is not absolute");
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("URI is not hierarchical");
        }
        if (!uri.getScheme().equalsIgnoreCase("file")) {
            throw new IllegalArgumentException("URI scheme is not \"file\"");
        }
        this.path = Disk.normal(LocalPath.pathOf(uri));
    }

    // A normal child below a normal parent.
    private static String resolved(String parent, String child) {
        if (child.isEmpty()) {
            return parent;
        }
        if (child.charAt(0) == '/') {
            return parent.equals("/") ? child : parent + child;
        }
        return parent.equals("/") ? "/" + child : parent + "/" + child;
    }

    // Whether the host may be asked of the path at all.
    private boolean valid() {
        return path.indexOf('\u0000') < 0;
    }

    public String getName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public String getParent() {
        return Disk.above(path);
    }

    public File getParentFile() {
        String parent = getParent();
        return parent == null ? null : new File(parent);
    }

    public String getPath() {
        return path;
    }

    public boolean isAbsolute() {
        return path.startsWith("/");
    }

    public String getAbsolutePath() {
        if (isAbsolute()) {
            return path;
        }
        String directory = Disk.workingDirectory();
        return directory == null ? path : resolved(directory, path);
    }

    public File getAbsoluteFile() {
        return new File(getAbsolutePath());
    }

    // The absolute path with its links resolved and its . and .. taken out; a link in
    // the last name that leads nowhere is kept, as a JDK keeps it.
    public String getCanonicalPath() throws IOException {
        if (!valid()) {
            throw new IOException("Invalid file path");
        }
        String absolute = getAbsolutePath();
        if (kind() == Disk.NONE && Disk.stat(path, false)[Disk.KIND] == Disk.LINK) {
            // where the working directory has no name a relative name stays as it is
            String above = Disk.above(absolute);
            if (above == null) {
                return absolute;
            }
            String real = Disk.real(above);
            return real.equals("/") ? "/" + getName() : real + "/" + getName();
        }
        return Disk.real(absolute);
    }

    public File getCanonicalFile() throws IOException {
        return new File(getCanonicalPath());
    }

    // file: and the absolute path, a directory's ending in a slash, a character past
    // ASCII escaped only where it is a control or space character. Where the working
    // directory has no name, a relative path is file: and the path.
    public URI toURI() {
        String absolute = path.isEmpty() && Disk.workingDirectory() == null ? "." : getAbsolutePath();
        if (!absolute.endsWith("/") && Disk.kind(absolute) == Disk.DIRECTORY) {
            absolute = absolute + "/";
        }
        return URI.create("file:" + LocalPath.quoted(absolute, true));
    }

    public static File[] listRoots() {
        return new File[] { new File("/") };
    }

    public Path toPath() {
        return Path.of(path);
    }

    // What the host is asked of: the path, the working directory where it is empty.
    private String host() {
        return path.isEmpty() ? "." : path;
    }

    // What the host's stat says of the path, its links followed.
    private long[] stat() {
        return valid() ? Disk.stat(host(), true) : new long[] { Disk.NONE, Disk.NOT_THERE };
    }

    private int kind() {
        return (int) stat()[Disk.KIND];
    }

    public boolean exists() {
        return kind() != Disk.NONE;
    }

    public boolean isDirectory() {
        return kind() == Disk.DIRECTORY;
    }

    public boolean isFile() {
        return kind() == Disk.FILE;
    }

    public boolean isHidden() {
        return getName().startsWith(".");
    }

    // What the host's access(2) says. A host with no permission bits (a .wasm) is not
    // asked: a file is read by opening it, a directory by listing it.
    public boolean canRead() {
        if (!valid()) {
            return false;
        }
        if (Disk.modes()) {
            return Disk.access(host(), 4);
        }
        int kind = kind();
        if (kind == Disk.DIRECTORY) {
            return Disk.names(host()) != null;
        }
        if (kind != Disk.NONE) {
            Object file = Disk.open(path, Disk.READ);
            if (file != null) {
                Disk.close(file);
                return true;
            }
        }
        return false;
    }

    // What the host's access(2) says. A host with no permission bits (a .wasm) is not
    // asked: a file is written by opening it to write over what is there, and nothing
    // written; whether a directory may be written cannot be told, which is false.
    public boolean canWrite() {
        if (!valid()) {
            return false;
        }
        if (Disk.modes()) {
            return Disk.access(host(), 2);
        }
        int kind = kind();
        if (kind == Disk.DIRECTORY) {
            return false;
        }
        if (kind != Disk.NONE) {
            Object file = Disk.open(path, Disk.OVERWRITE);
            if (file != null) {
                Disk.close(file);
                return true;
            }
        }
        return false;
    }

    // What the host's access(2) says; false where it has no permission bits, which
    // cannot tell.
    public boolean canExecute() {
        return valid() && Disk.modes() && Disk.access(host(), 1);
    }

    // Gives the owner's permission (ownerOnly) or everybody's, links followed, keeping
    // the other bits: false where nothing is there or the host has no permission bits.
    private boolean permit(int owner, boolean on, boolean ownerOnly) {
        if (!Disk.modes()) {
            return false;
        }
        long[] stat = stat();
        if (stat[Disk.KIND] == Disk.NONE) {
            return false;
        }
        int bits = ownerOnly ? owner : owner | owner >> 3 | owner >> 6;
        int mode = (int) stat[Disk.MODE];
        return Disk.setMode(host(), on ? mode | bits : mode & ~bits) == 0;
    }

    public boolean setReadable(boolean readable, boolean ownerOnly) {
        return permit(0400, readable, ownerOnly);
    }

    public boolean setReadable(boolean readable) {
        return permit(0400, readable, true);
    }

    public boolean setWritable(boolean writable, boolean ownerOnly) {
        return permit(0200, writable, ownerOnly);
    }

    public boolean setWritable(boolean writable) {
        return permit(0200, writable, true);
    }

    public boolean setExecutable(boolean executable, boolean ownerOnly) {
        return permit(0100, executable, ownerOnly);
    }

    public boolean setExecutable(boolean executable) {
        return permit(0100, executable, true);
    }

    // Nobody may write it.
    public boolean setReadOnly() {
        return permit(0200, false, false);
    }

    // The bytes of the partition the path is on, as the host says: 0 where the path names
    // nothing, or the host does not say (a .wasm).
    public long getTotalSpace() {
        return valid() ? Disk.space(host(), 0) : 0;
    }

    public long getFreeSpace() {
        return valid() ? Disk.space(host(), 1) : 0;
    }

    public long getUsableSpace() {
        return valid() ? Disk.space(host(), 2) : 0;
    }

    // The size the host's stat says, a directory's and one of a file the program may not
    // read too.
    public long length() {
        long[] stat = stat();
        return stat[Disk.KIND] == Disk.NONE ? 0 : stat[Disk.SIZE];
    }

    // In milliseconds, those of the host's nanoseconds.
    public long lastModified() {
        long[] stat = stat();
        return stat[Disk.KIND] == Disk.NONE ? 0 : Math.floorDiv(stat[Disk.MODIFIED], 1_000_000L);
    }

    // The time of last writing, by the path; the time of last access is kept to the
    // microsecond, as a JDK's utimes keeps it.
    public boolean setLastModified(long time) {
        if (time < 0) {
            throw new IllegalArgumentException("Negative time");
        }
        long[] stat = stat();
        if (stat[Disk.KIND] == Disk.NONE) {
            return false;
        }
        return Disk.setTimes(host(), true, time * 1_000_000L, Math.floorDiv(stat[Disk.ACCESSED], 1000L) * 1000L) == 0;
    }

    public boolean createNewFile() throws IOException {
        if (!valid()) {
            throw new IOException("Invalid file path");
        }
        if (path.isEmpty()) {
            throw new IOException(Disk.words(Disk.MISSING));
        }
        // a link is there even where it leads nowhere
        if (kind() != Disk.NONE || Disk.stat(path, false)[Disk.KIND] != Disk.NONE) {
            return false;
        }
        Object file = Disk.open(path, Disk.APPEND);
        if (file == null) {
            throw new IOException(Disk.words(Disk.refused(path, true)));
        }
        Disk.close(file);
        return true;
    }

    // A file made new in a directory, /tmp where none is given, named by the last name of
    // the prefix, a number no file is likely to have and the suffix (.tmp where none is
    // given); a name the suffix puts in another directory is refused.
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (prefix.length() < 3) {
            throw new IllegalArgumentException("Prefix string \"" + prefix + "\" too short: length must be at least 3");
        }
        String start = new File(prefix).getName();
        String end = suffix == null ? ".tmp" : suffix;
        File in = directory == null ? new File(Disk.TEMPORARY) : directory;
        while (true) {
            String name = Disk.unlikely(start, end);
            if (name.indexOf('/') >= 0) {
                throw new IOException("Unable to create temporary file, " + name);
            }
            File made = new File(in, name);
            if (made.createNewFile()) {
                return made;
            }
        }
    }

    public static File createTempFile(String prefix, String suffix) throws IOException {
        return createTempFile(prefix, suffix, null);
    }

    public boolean delete() {
        return valid() && !path.isEmpty() && Disk.delete(path);
    }

    public void deleteOnExit() {
        if (valid()) {
            // named in full: an import would read the class with File, for every program
            rontolisp.internal.Shutdown.deleteOnExit(path);
        }
    }

    public boolean mkdir() {
        if (kind() != Disk.NONE) {
            return false;
        }
        String parent = getParent();
        if (parent != null && Disk.kind(parent) != Disk.DIRECTORY) {
            return false;
        }
        return Disk.makeDirectories(path) && Disk.kind(path) == Disk.DIRECTORY;
    }

    public boolean mkdirs() {
        if (kind() != Disk.NONE) {
            return false;
        }
        return Disk.makeDirectories(path) && Disk.kind(path) == Disk.DIRECTORY;
    }

    public boolean renameTo(File dest) {
        if (dest == null) {
            throw new NullPointerException();
        }
        return valid() && dest.valid() && !path.isEmpty() && !dest.path.isEmpty() && Disk.rename(path, dest.path);
    }

    public String[] list() {
        return valid() ? Disk.names(host()) : null;
    }

    // A name the directory lists as a File: below this one, or as it is where this one is
    // the empty path.
    private File child(String name) {
        return path.isEmpty() ? new File(name) : new File(this, name);
    }

    public String[] list(FilenameFilter filter) {
        String[] names = list();
        if (names == null || filter == null) {
            return names;
        }
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (filter.accept(this, name)) {
                kept.add(name);
            }
        }
        return kept.toArray(new String[0]);
    }

    public File[] listFiles() {
        String[] names = list();
        if (names == null) {
            return null;
        }
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = child(names[i]);
        }
        return files;
    }

    public File[] listFiles(FilenameFilter filter) {
        String[] names = list();
        if (names == null) {
            return null;
        }
        List<File> kept = new ArrayList<>();
        for (String name : names) {
            if (filter == null || filter.accept(this, name)) {
                kept.add(child(name));
            }
        }
        return kept.toArray(new File[0]);
    }

    public File[] listFiles(FileFilter filter) {
        String[] names = list();
        if (names == null) {
            return null;
        }
        List<File> kept = new ArrayList<>();
        for (String name : names) {
            File file = child(name);
            if (filter == null || filter.accept(file)) {
                kept.add(file);
            }
        }
        return kept.toArray(new File[0]);
    }

    public int compareTo(File pathname) {
        return path.compareTo(pathname.path);
    }

    public boolean equals(Object obj) {
        return obj instanceof File other && path.equals(other.path);
    }

    public int hashCode() {
        return path.hashCode() ^ 1234321;
    }

    public String toString() {
        return path;
    }
}

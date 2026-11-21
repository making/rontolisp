// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import rontolisp.internal.Disk;

// A path and the questions a program asks the host's file system of it. The path is the
// one given with each run of slashes one slash and no slash at its end but the root's:
// what a JDK answers on a Unix host, read off its answers (getParent, getName, the paths
// of a parent and a child, an empty parent meaning the root). Relative paths are resolved
// by the host against the working directory, which getAbsolutePath names where the host
// has one to name. A path holding a NUL names nothing.
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

    public String getCanonicalPath() throws IOException {
        if (!valid()) {
            throw new IOException("Invalid file path");
        }
        return Disk.real(getAbsolutePath());
    }

    public File getCanonicalFile() throws IOException {
        return new File(getCanonicalPath());
    }

    public Path toPath() {
        return Path.of(path);
    }

    private int kind() {
        return valid() && !path.isEmpty() ? Disk.kind(path) : Disk.NONE;
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

    // A file is read by opening it, a directory by listing it: what the host lets a
    // program do, which is what a .wasm can be asked of at all.
    public boolean canRead() {
        int kind = kind();
        if (kind == Disk.DIRECTORY) {
            return Disk.names(path) != null;
        }
        if (kind == Disk.FILE) {
            Object file = Disk.open(path, Disk.READ);
            if (file != null) {
                Disk.close(file);
                return true;
            }
        }
        return false;
    }

    // A file is written by opening it to write over what is there, and nothing written;
    // whether a directory may be written the host is not asked.
    public boolean canWrite() {
        int kind = kind();
        if (kind == Disk.DIRECTORY) {
            throw new UnsupportedOperationException("canWrite of a directory: " + path);
        }
        if (kind == Disk.FILE) {
            Object file = Disk.open(path, Disk.OVERWRITE);
            if (file != null) {
                Disk.close(file);
                return true;
            }
        }
        return false;
    }

    public long length() {
        if (kind() != Disk.FILE) {
            return 0;
        }
        long size = Disk.size(path);
        return size < 0 ? 0 : size;
    }

    public long lastModified() {
        return kind() == Disk.NONE ? 0 : Disk.modified(path);
    }

    public boolean createNewFile() throws IOException {
        if (!valid()) {
            throw new IOException("Invalid file path");
        }
        if (kind() != Disk.NONE) {
            return false;
        }
        Object file = Disk.open(path, Disk.APPEND);
        if (file == null) {
            throw new IOException(Disk.words(Disk.refused(path, true)));
        }
        Disk.close(file);
        return true;
    }

    public boolean delete() {
        return valid() && !path.isEmpty() && Disk.delete(path);
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
        return valid() && !path.isEmpty() ? Disk.names(path) : null;
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
            files[i] = new File(this, names[i]);
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
                kept.add(new File(this, name));
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
            File file = new File(this, name);
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

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;
import rontolisp.internal.Disk;
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
// host, read off its answers.
public final class Files {

    private Files() {
    }

    private static String host(Path path) {
        return ((LocalPath) Objects.requireNonNull(path)).host();
    }

    private static int kind(Path path) {
        return Disk.kind(host(path));
    }

    // The exception a JDK throws where the host refused a path: an operation that makes
    // it is refused where it could not be made.
    private static IOException refusal(Path path, boolean making) {
        String file = path.toString();
        int refused = Disk.refused(host(path), making);
        return refused == Disk.MISSING ? new NoSuchFileException(file)
                : refused == Disk.DENIED ? new AccessDeniedException(file)
                        : new FileSystemException(file, null, Disk.words(refused));
    }

    // ---- streams

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
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
        if (createNew && kind != Disk.NONE) {
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
                return new FileOutputStream(name, append);
            }
            // written from the start over what is there
            RandomAccessFile file = new RandomAccessFile(name, "rw");
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
        return new BufferedReader(new StrictReader(newInputStream(path), cs));
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
        try (InputStream in = newInputStream(path)) {
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
        return kind(path) != Disk.NONE;
    }

    public static boolean notExists(Path path, LinkOption... options) {
        return kind(path) == Disk.NONE;
    }

    public static boolean isDirectory(Path path, LinkOption... options) {
        return kind(path) == Disk.DIRECTORY;
    }

    public static boolean isRegularFile(Path path, LinkOption... options) {
        return kind(path) == Disk.FILE;
    }

    public static boolean isReadable(Path path) {
        return new java.io.File(host(path)).canRead();
    }

    public static boolean isWritable(Path path) {
        return new java.io.File(host(path)).canWrite();
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
        int kind = kind(path);
        if (kind == Disk.NONE) {
            throw new NoSuchFileException(path.toString());
        }
        long size = kind == Disk.FILE ? Disk.size(host(path)) : 0;
        if (size < 0) {
            throw refusal(path, false);
        }
        return size;
    }

    public static Path createFile(Path path) throws IOException {
        String name = host(path);
        if (Disk.kind(name) != Disk.NONE) {
            throw new FileAlreadyExistsException(path.toString());
        }
        Object made = Disk.open(name, Disk.APPEND);
        if (made == null) {
            throw refusal(path, true);
        }
        Disk.close(made);
        return path;
    }

    public static Path createDirectory(Path dir) throws IOException {
        String name = host(dir);
        if (Disk.kind(name) != Disk.NONE) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        String above = Disk.above(name);
        if (above != null && Disk.kind(above) != Disk.DIRECTORY || !Disk.makeDirectories(name)
                || Disk.kind(name) != Disk.DIRECTORY) {
            throw refusal(dir, true);
        }
        return dir;
    }

    // A file in the way of a directory to be made is named in the path made absolute.
    public static Path createDirectories(Path dir) throws IOException {
        String name = host(dir);
        int kind = Disk.kind(name);
        if (kind == Disk.DIRECTORY) {
            return dir;
        }
        if (kind == Disk.FILE) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!Disk.makeDirectories(name) || Disk.kind(name) != Disk.DIRECTORY) {
            IOException refused = refusal(dir, true);
            if (refused instanceof FileSystemException named && named.getReason() != null) {
                throw new FileSystemException(dir.toAbsolutePath().toString(), null, named.getReason());
            }
            throw refused;
        }
        return dir;
    }

    public static void delete(Path path) throws IOException {
        String name = host(path);
        int kind = Disk.kind(name);
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
        if (kind(path) == Disk.NONE) {
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

    // Makes way for a copy or a move: a target there is refused, or deleted when the
    // options replace it.
    private static void cleared(Path target, boolean replace) throws IOException {
        if (kind(target) != Disk.NONE) {
            if (!replace) {
                throw new FileAlreadyExistsException(target.toString());
            }
            delete(target);
        }
    }

    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        int kind = kind(source);
        if (kind == Disk.NONE) {
            throw new NoSuchFileException(source.toString());
        }
        if (kind(target) != Disk.NONE && isSameFile(source, target)) {
            return target;
        }
        cleared(target, replaces(options));
        if (kind == Disk.DIRECTORY) {
            // a directory's entries are not copied
            createDirectory(target);
            return target;
        }
        try (InputStream in = newInputStream(source); OutputStream out = newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            in.transferTo(out);
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
        try (InputStream in = newInputStream(source)) {
            return in.transferTo(out);
        }
    }

    public static Path move(Path source, Path target, CopyOption... options) throws IOException {
        if (kind(source) == Disk.NONE) {
            throw new NoSuchFileException(source.toString());
        }
        if (kind(target) != Disk.NONE && isSameFile(source, target)) {
            return target;
        }
        cleared(target, replaces(options));
        if (!Disk.rename(host(source), host(target))) {
            IOException refused = refusal(target, true);
            if (refused instanceof NoSuchFileException) {
                throw new NoSuchFileException(source.toString(), target.toString(), null);
            }
            throw refused;
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
            int kind = kind(dir);
            throw kind == Disk.NONE ? new NoSuchFileException(dir.toString())
                    : kind == Disk.FILE ? new NotDirectoryException(dir.toString()) : new AccessDeniedException(dir.toString());
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

    public static Stream<Path> walk(Path start, FileVisitOption... options) throws IOException {
        return walk(start, Integer.MAX_VALUE, options);
    }

    // Depth first, a directory before what it holds, each directory's entries in the
    // host's order and listed when the walk reaches it.
    public static Stream<Path> walk(Path start, int maxDepth, FileVisitOption... options) throws IOException {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("'maxDepth' is negative");
        }
        if (kind(start) == Disk.NONE) {
            throw new NoSuchFileException(start.toString());
        }
        Deque<Iterator<Path>> open = new ArrayDeque<>();
        return ObjectFlow.over(new Iterator<Path>() {
            private Path next = start;

            public boolean hasNext() {
                while (next == null && !open.isEmpty()) {
                    Iterator<Path> top = open.peek();
                    if (top.hasNext()) {
                        next = top.next();
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
                if (open.size() < maxDepth && kind(walked) == Disk.DIRECTORY) {
                    try {
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

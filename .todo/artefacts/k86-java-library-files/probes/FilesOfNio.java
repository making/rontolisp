import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// A program's files through java.nio.file, in the directory it runs in: a path's names and
// what it resolves and relativizes to, what Files makes, reads, copies, moves and lists, the
// strict codecs of its text and the exceptions it throws, with the words of each.
public class FilesOfNio {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            Object value = io.run();
            System.out.println(label + ": " + (value instanceof byte[] bytes ? Arrays.toString(bytes) : value));
        }
        catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }

    static String names(Path p) {
        List<String> names = new ArrayList<>();
        for (Path name : p) {
            names.add(name.toString());
        }
        return names.toString();
    }

    public static void main(String[] args) {
        Path empty = Path.of("");
        Path here = empty.toAbsolutePath();
        show("of", () -> Path.of("a//b/", "", "/c") + " " + Paths.get("", "x") + " " + Path.of("/..") + " " + empty
                + " " + Path.of("a", "b").equals(Path.of("a/b")) + " " + Path.of("a/b").hashCode() + " "
                + Path.of("a/b").compareTo(Path.of("a-b")));
        show("names", () -> {
            StringBuilder seen = new StringBuilder();
            for (String text : new String[] { "", "/", "a", "/a/b/c", "a/./../b" }) {
                Path p = Path.of(text);
                seen.append('[').append(p).append("] ").append(p.getFileName()).append(' ').append(p.getParent())
                        .append(' ').append(p.getRoot()).append(' ').append(p.getNameCount()).append(' ')
                        .append(names(p)).append(' ').append(p.normalize()).append(' ').append(p.isAbsolute())
                        .append("; ");
            }
            return seen;
        });
        show("parts", () -> Path.of("/a/b/c").getName(1) + " " + Path.of("a/b/c").subpath(1, 3) + " "
                + Path.of("/a/b/c").startsWith("/a") + " " + Path.of("a/b/c").startsWith("a/b/") + " "
                + Path.of("a/bc").startsWith("a/b") + " " + Path.of("/a/b/c").endsWith("b/c") + " "
                + Path.of("a").endsWith("") + " " + empty.startsWith(""));
        show("part refused", () -> Path.of("a/b").subpath(1, 1));
        show("resolved", () -> Path.of("a").resolve("b/c") + " " + Path.of("a").resolve("/x") + " " + empty.resolve("b")
                + " " + Path.of("/").resolve("b") + " " + Path.of("a/b").resolveSibling("c") + " "
                + Path.of("a").resolveSibling("c") + " " + Path.of("a/b").resolve(""));
        show("relativized", () -> Path.of("a/b").relativize(Path.of("a/c/d")) + " "
                + Path.of("/a/b").relativize(Path.of("/c")) + " " + Path.of("a/./b").relativize(Path.of("a/c")) + " "
                + Path.of("a").relativize(Path.of("../b")) + " [" + Path.of("a/b").relativize(Path.of("a/b")) + "]");
        show("relativize refused", () -> Path.of("../a").relativize(Path.of("b")));
        show("relativize mixed", () -> Path.of("a").relativize(Path.of("/a")));
        show("absolute", () -> Path.of("x/y").toAbsolutePath().equals(here.resolve("x/y")) + " "
                + here.isAbsolute() + " " + Path.of("x").toFile() + " " + empty.toFile().getPath().isEmpty());
        show("nul", () -> {
            try {
                return Path.of("a", "b\u0000");
            }
            catch (InvalidPathException e) {
                return e.getMessage().replace('\u0000', '@') + " " + e.getIndex() + " " + e.getReason();
            }
        });
        Path dir = Path.of("out", "a");
        show("made", () -> Files.createDirectories(dir) + " " + Files.createDirectories(Path.of("out/a/")) + " "
                + Files.isDirectory(dir) + " " + Files.exists(dir) + " " + Files.notExists(dir) + " "
                + Files.isRegularFile(dir) + " " + Files.isDirectory(empty));
        Path f = dir.resolve("f.txt");
        show("written", () -> Files.writeString(f, "héllo\nline 2\r\nend") + " " + Files.size(f) + " "
                + Files.isRegularFile(f));
        show("read", () -> Files.readString(f).replace("\r\n", "|").replace('\n', '|') + " " + Files.readAllLines(f)
                + " " + Files.readAllBytes(f).length);
        show("lines", () -> {
            try (Stream<String> lines = Files.lines(f)) {
                return lines.map(String::toUpperCase).collect(Collectors.joining(","));
            }
        });
        show("bytes", () -> Arrays.toString(Files.readAllBytes(Files.write(dir.resolve("b.bin"), new byte[] { 1, -2 }))));
        show("lines written", () -> Files.readString(Files.write(dir.resolve("l.txt"), List.of("one", "two"))).replace('\n', '|'));
        show("appended", () -> Files.readAllLines(
                Files.writeString(dir.resolve("l.txt"), "three", StandardOpenOption.APPEND)));
        show("options", () -> {
            Path n = dir.resolve("n.txt");
            Files.writeString(n, "new", StandardOpenOption.CREATE_NEW);
            Files.writeString(n, "N", StandardOpenOption.WRITE);
            String first = Files.readString(n);
            Files.writeString(n, "M", StandardOpenOption.CREATE);
            return first + " " + Files.readString(n);
        });
        show("create new existing", () -> Files.writeString(f, "x", StandardOpenOption.CREATE_NEW));
        show("write missing", () -> Files.writeString(dir.resolve("none.txt"), "x", StandardOpenOption.APPEND));
        show("append truncate", () -> Files.newOutputStream(f, StandardOpenOption.APPEND,
                StandardOpenOption.TRUNCATE_EXISTING));
        show("reader writer", () -> {
            Path w = dir.resolve("w.txt");
            try (BufferedWriter out = Files.newBufferedWriter(w, StandardCharsets.UTF_16BE)) {
                out.write("ab");
                out.newLine();
            }
            try (BufferedReader in = Files.newBufferedReader(w, StandardCharsets.UTF_16BE)) {
                return Files.size(w) + " " + in.readLine() + " " + in.readLine();
            }
        });
        show("streams", () -> {
            try (OutputStream out = Files.newOutputStream(dir.resolve("s.bin"))) {
                out.write(new byte[] { 7, 8, 9 });
            }
            try (InputStream in = Files.newInputStream(dir.resolve("s.bin"))) {
                return in.read() + " " + in.readAllBytes().length;
            }
        });
        // the strict codecs
        Path bad = dir.resolve("bad.txt");
        show("malformed", () -> Files.write(bad, new byte[] { 'a', (byte) 0xe9, 'b', '\n', 'c' }) + " "
                + Files.readString(bad, StandardCharsets.ISO_8859_1).length());
        show("readString malformed", () -> Files.readString(bad));
        show("readAllLines malformed", () -> Files.readAllLines(bad));
        show("lines malformed", () -> {
            try (Stream<String> lines = Files.lines(bad)) {
                return lines.count();
            }
        });
        show("ascii", () -> Files.readString(bad, StandardCharsets.US_ASCII));
        show("cut", () -> Files.readString(Files.write(dir.resolve("cut.txt"), new byte[] { 'a', (byte) 0xe2, (byte) 0x82 })));
        show("surrogate", () -> Files.readString(Files.write(dir.resolve("sur.txt"),
                new byte[] { 'a', (byte) 0xed, (byte) 0xa0, (byte) 0x80, 'b' })));
        show("unmappable", () -> Files.writeString(dir.resolve("u.txt"), "a€b", StandardCharsets.US_ASCII));
        show("unmappable left", () -> Files.exists(dir.resolve("u.txt")));
        show("unmappable lines", () -> Files.write(dir.resolve("u2.txt"), List.of("ok", "é"), StandardCharsets.US_ASCII));
        show("unmappable left lines", () -> Files.readString(dir.resolve("u2.txt")).replace('\n', '|'));
        show("lone surrogate", () -> Files.writeString(dir.resolve("s.txt"), "a\ud800b"));
        // copies, moves and deletes
        show("copied", () -> Files.copy(f, dir.resolve("g.txt")) + " " + Files.mismatch(f, dir.resolve("g.txt")) + " "
                + Files.isSameFile(f, Path.of("out/./a/f.txt")) + " " + Files.isSameFile(f, dir.resolve("g.txt")));
        show("copy existing", () -> Files.copy(f, dir.resolve("g.txt")));
        show("copy replacing", () -> Files.copy(dir.resolve("b.bin"), dir.resolve("g.txt"),
                StandardCopyOption.REPLACE_EXISTING) + " " + Arrays.toString(Files.readAllBytes(dir.resolve("g.txt"))));
        show("copy streams", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long n = Files.copy(f, out);
            try (InputStream in = Files.newInputStream(f)) {
                return n + " " + Files.copy(in, dir.resolve("h.txt")) + " " + out.size();
            }
        });
        show("copy directory", () -> Files.copy(dir, Path.of("out/a2")) + " " + Files.list(Path.of("out/a2")).count());
        show("moved", () -> Files.move(dir.resolve("g.txt"), dir.resolve("m.txt")) + " " + Files.exists(dir.resolve("g.txt")));
        show("move existing", () -> Files.move(dir.resolve("h.txt"), dir.resolve("m.txt")));
        show("move into missing", () -> Files.move(dir.resolve("m.txt"), Path.of("out/nope/m.txt")));
        show("move missing", () -> Files.move(dir.resolve("g.txt"), dir.resolve("x.txt")));
        show("deleted", () -> {
            Files.delete(dir.resolve("m.txt"));
            return Files.deleteIfExists(dir.resolve("h.txt")) + " " + Files.deleteIfExists(dir.resolve("h.txt"));
        });
        show("delete missing", () -> {
            Files.delete(dir.resolve("m.txt"));
            return "deleted";
        });
        show("delete full", () -> {
            Files.delete(dir);
            return "deleted";
        });
        // listing and walking: the host's order, which differs from one file system to
        // another, sorted but where a directory has one name
        Path list = Path.of("out/list");
        show("listed", () -> {
            Files.createDirectory(list);
            for (String name : new String[] { "b", "a", "c", "10", "9", "B", ".dot" }) {
                Files.createFile(list.resolve(name));
            }
            Files.createDirectories(list.resolve("dir/sub"));
            Files.createFile(list.resolve("dir/x"));
            try (Stream<Path> s = Files.list(list)) {
                return s.map(Path::toString).sorted().collect(Collectors.joining(" "));
            }
        });
        show("walked", () -> {
            try (Stream<Path> s = Files.walk(list)) {
                return s.map(Path::toString).sorted().collect(Collectors.joining(" "));
            }
        });
        show("walked down", () -> {
            Files.createDirectories(Path.of("out/chain/a/b"));
            Files.writeString(Path.of("out/chain/a/b/c.txt"), "c");
            try (Stream<Path> s = Files.walk(Path.of("out/chain/"))) {
                return s.map(Path::toString).collect(Collectors.joining(" "));
            }
        });
        show("walked depth 1", () -> {
            try (Stream<Path> s = Files.walk(list, 1)) {
                return s.count();
            }
        });
        show("hidden", () -> Files.isHidden(list.resolve(".dot")) + " " + Files.isHidden(list.resolve("a")));
        show("list missing", () -> Files.list(Path.of("out/none")));
        show("list a file", () -> Files.list(f));
        show("walk negative", () -> Files.walk(list, -1));
        // the refusals of making and reading
        show("directory again", () -> Files.createDirectory(dir));
        show("directory in missing", () -> Files.createDirectory(Path.of("out/x/y")));
        show("file again", () -> Files.createFile(f));
        show("file in missing", () -> Files.createFile(Path.of("out/nope/x.txt")));
        show("directories over a file", () -> Files.createDirectories(f));
        show("directories under a file", () -> {
            try {
                return Files.createDirectories(f.resolve("x"));
            }
            catch (FileSystemException e) {
                return e.getClass().getName() + ": " + here.relativize(Path.of(e.getFile())) + " " + e.getReason();
            }
        });
        show("read missing", () -> Files.readString(dir.resolve("missing.txt")));
        show("read directory", () -> Files.readAllBytes(dir));
        show("read under a file", () -> Files.readString(f.resolve("x")));
        show("write directory", () -> Files.writeString(dir, "x"));
        show("size missing", () -> Files.size(dir.resolve("missing.txt")));
        show("real missing", () -> Path.of("out/none/..").toRealPath());
        show("real", () -> Path.of("out/a/../a/./f.txt").toRealPath().equals(here.toRealPath().resolve("out/a/f.txt")));
        show("read locked", () -> Files.readString(Path.of("locked.txt")));
        show("write locked", () -> Files.writeString(Path.of("locked.txt"), "x"));
        show("write read-only", () -> Files.writeString(Path.of("ro/x.txt"), "x"));
        show("readable", () -> Files.isReadable(f) + " " + Files.isReadable(Path.of("locked.txt")) + " "
                + Files.isWritable(f) + " " + Files.isReadable(Path.of("ro")));
        show("texts", () -> new FileSystemException("a", "b", "r").getMessage() + "|"
                + new FileSystemException(null, null, "r").getMessage() + "|" + new FileSystemException("a").getMessage()
                + "|" + new InvalidPathException("in", "why", 3).getMessage());
    }
}

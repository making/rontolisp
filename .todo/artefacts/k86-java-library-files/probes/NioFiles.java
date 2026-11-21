import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// What Files does in a directory of its own (the run's) and the words of what it
// refuses. Run where locked.txt (mode 000) and ro/ (mode 555) were made beforehand.
public class NioFiles {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            Object value = io.run();
            System.out.println(label + " = " + (value instanceof byte[] bytes ? Arrays.toString(bytes) : value));
        }
        catch (Exception e) {
            System.out.println(label + " ! " + e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                System.out.println("  cause " + cause.getClass().getName() + ": " + cause.getMessage());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path t = Path.of("t");
        show("exists notExists", () -> Files.exists(t) + " " + Files.notExists(t));
        show("createDirectory", () -> Files.createDirectory(t));
        show("createDirectory again", () -> Files.createDirectory(t));
        show("createDirectory in missing", () -> Files.createDirectory(Path.of("t/a/b")));
        show("createDirectories", () -> Files.createDirectories(Path.of("t/a/b")));
        show("createDirectories again", () -> Files.createDirectories(Path.of("t/a/b/")));
        show("isDirectory isRegularFile", () -> Files.isDirectory(t) + " " + Files.isRegularFile(t) + " "
                + Files.exists(t, LinkOption.NOFOLLOW_LINKS));
        Path f = t.resolve("f.txt");
        show("createFile", () -> Files.createFile(f));
        show("createFile again", () -> Files.createFile(f));
        show("createFile in missing", () -> Files.createFile(Path.of("t/nope/x.txt")));
        show("createDirectories over a file", () -> Files.createDirectories(f));
        show("createDirectories under a file", () -> Files.createDirectories(f.resolve("x")));
        show("size", () -> Files.size(f));
        show("size missing", () -> Files.size(t.resolve("missing")));
        show("writeString", () -> Files.writeString(f, "héllo\n€😀\nend"));
        show("size", () -> Files.size(f));
        show("readString", () -> Files.readString(f));
        show("readAllBytes", () -> Files.readAllBytes(f));
        show("readAllLines", () -> Files.readAllLines(f));
        show("lines", () -> {
            try (Stream<String> lines = Files.lines(f)) {
                return lines.map(String::length).collect(Collectors.toList());
            }
        });
        show("write bytes", () -> Files.write(t.resolve("b.bin"), new byte[] { 1, 2, -1 }));
        show("readAllBytes", () -> Files.readAllBytes(t.resolve("b.bin")));
        show("write lines", () -> Files.write(t.resolve("l.txt"), List.of("one", "two", "")));
        show("readString lines", () -> Files.readString(t.resolve("l.txt")).replace("\n", "|"));
        show("append", () -> Files.writeString(t.resolve("l.txt"), "three", StandardOpenOption.APPEND));
        show("readAllLines", () -> Files.readAllLines(t.resolve("l.txt")));
        show("line ends", () -> {
            Files.writeString(t.resolve("e.txt"), "a\r\nb\rc\n\nd\r");
            return Files.readAllLines(t.resolve("e.txt")) + " " + Files.lines(t.resolve("e.txt")).toList();
        });
        show("create new existing", () -> Files.writeString(f, "x", StandardOpenOption.CREATE_NEW));
        show("write missing no create", () -> Files.writeString(t.resolve("none.txt"), "x", StandardOpenOption.WRITE));
        show("append missing", () -> Files.writeString(t.resolve("none.txt"), "x", StandardOpenOption.APPEND));
        show("append and truncate", () -> Files.writeString(t.resolve("none.txt"), "x", StandardOpenOption.APPEND,
                StandardOpenOption.TRUNCATE_EXISTING));
        show("read option", () -> Files.writeString(t.resolve("none.txt"), "x", StandardOpenOption.READ));
        show("create new", () -> Files.writeString(t.resolve("new.txt"), "new", StandardOpenOption.CREATE_NEW));
        show("write existing no truncate", () -> {
            Files.writeString(t.resolve("new.txt"), "N", StandardOpenOption.WRITE);
            return Files.readString(t.resolve("new.txt"));
        });
        show("create no truncate", () -> {
            Files.writeString(t.resolve("new.txt"), "M", StandardOpenOption.CREATE);
            return Files.readString(t.resolve("new.txt"));
        });
        // the refusals
        show("readString missing", () -> Files.readString(t.resolve("missing.txt")));
        show("readString directory", () -> Files.readString(t));
        show("readAllBytes directory", () -> Files.readAllBytes(t));
        show("readAllLines directory", () -> Files.readAllLines(t));
        show("readString locked", () -> Files.readString(Path.of("locked.txt")));
        show("writeString locked", () -> Files.writeString(Path.of("locked.txt"), "x"));
        show("writeString in missing", () -> Files.writeString(Path.of("t/nope/x.txt"), "x"));
        show("writeString directory", () -> Files.writeString(t, "x"));
        show("writeString read-only directory", () -> Files.writeString(Path.of("ro/x.txt"), "x"));
        show("writeString under a file", () -> Files.writeString(f.resolve("x"), "x"));
        show("readString under a file", () -> Files.readString(f.resolve("x")));
        show("newInputStream missing", () -> Files.newInputStream(t.resolve("missing.txt")));
        show("newInputStream directory", () -> {
            try (InputStream in = Files.newInputStream(t)) {
                return in.read();
            }
        });
        show("newBufferedReader missing", () -> Files.newBufferedReader(t.resolve("missing.txt")));
        show("newBufferedWriter in missing", () -> Files.newBufferedWriter(Path.of("t/nope/x.txt")));
        show("newOutputStream directory", () -> Files.newOutputStream(t));
        show("lines missing", () -> Files.lines(t.resolve("missing.txt")));
        // malformed input
        show("write malformed", () -> Files.write(t.resolve("bad.txt"), new byte[] { 'a', (byte) 0xe9, 'b', '\n', 'c' }));
        show("readString malformed", () -> Files.readString(t.resolve("bad.txt")));
        show("readAllLines malformed", () -> Files.readAllLines(t.resolve("bad.txt")));
        show("lines malformed", () -> {
            try (Stream<String> lines = Files.lines(t.resolve("bad.txt"))) {
                return lines.toList();
            }
        });
        show("newBufferedReader malformed", () -> {
            try (BufferedReader r = Files.newBufferedReader(t.resolve("bad.txt"))) {
                return r.readLine();
            }
        });
        show("readString latin", () -> Files.readString(t.resolve("bad.txt"), StandardCharsets.ISO_8859_1).length());
        show("readString ascii", () -> Files.readString(t.resolve("bad.txt"), StandardCharsets.US_ASCII));
        show("readString utf16", () -> Files.readString(t.resolve("bad.txt"), StandardCharsets.UTF_16));
        show("readString truncated", () -> {
            Files.write(t.resolve("cut.txt"), new byte[] { 'a', (byte) 0xe2, (byte) 0x82 });
            return Files.readString(t.resolve("cut.txt"));
        });
        show("readString surrogate", () -> {
            Files.write(t.resolve("sur.txt"), new byte[] { 'a', (byte) 0xed, (byte) 0xa0, (byte) 0x80, 'b' });
            return Files.readString(t.resolve("sur.txt"));
        });
        show("readString long", () -> {
            Files.write(t.resolve("long.txt"), new byte[] { (byte) 0xf0, (byte) 0x9f, (byte) 0x98, 'x' });
            return Files.readString(t.resolve("long.txt"));
        });
        show("writeString unmappable", () -> Files.writeString(t.resolve("u.txt"), "a€b", StandardCharsets.US_ASCII));
        show("u.txt after", () -> Files.exists(t.resolve("u.txt")) + " " + Files.size(t.resolve("u.txt")));
        show("writeString lone surrogate", () -> Files.writeString(t.resolve("s.txt"), "a\ud800b"));
        show("write lines unmappable", () -> Files.write(t.resolve("u2.txt"), List.of("é"), StandardCharsets.US_ASCII));
        show("newBufferedWriter unmappable", () -> {
            try (BufferedWriter w = Files.newBufferedWriter(t.resolve("u3.txt"), StandardCharsets.US_ASCII)) {
                w.write("oké");
            }
            return Files.size(t.resolve("u3.txt"));
        });
        // copy, move, delete
        show("copy", () -> Files.copy(f, t.resolve("g.txt")));
        show("copy existing", () -> Files.copy(f, t.resolve("g.txt")));
        show("copy replace", () -> Files.copy(t.resolve("b.bin"), t.resolve("g.txt"), StandardCopyOption.REPLACE_EXISTING));
        show("copied", () -> Files.readAllBytes(t.resolve("g.txt")));
        show("copy missing", () -> Files.copy(t.resolve("missing"), t.resolve("h.txt")));
        show("copy directory", () -> Files.copy(t.resolve("a"), t.resolve("a2")));
        show("copied directory", () -> Files.isDirectory(t.resolve("a2")) + " " + Files.exists(t.resolve("a2/b")));
        show("copy stream", () -> {
            try (InputStream in = Files.newInputStream(f)) {
                return Files.copy(in, t.resolve("i.txt"));
            }
        });
        show("copy to stream", () -> {
            try (OutputStream out = Files.newOutputStream(t.resolve("o.txt"))) {
                return Files.copy(f, out);
            }
        });
        show("move", () -> Files.move(t.resolve("g.txt"), t.resolve("m.txt")));
        show("move existing", () -> Files.move(t.resolve("i.txt"), t.resolve("m.txt")));
        show("move replace", () -> Files.move(t.resolve("i.txt"), t.resolve("m.txt"), StandardCopyOption.REPLACE_EXISTING));
        show("move missing", () -> Files.move(t.resolve("g.txt"), t.resolve("x.txt")));
        show("delete", () -> {
            Files.delete(t.resolve("m.txt"));
            return Files.exists(t.resolve("m.txt"));
        });
        show("delete missing", () -> {
            Files.delete(t.resolve("m.txt"));
            return "deleted";
        });
        show("deleteIfExists", () -> Files.deleteIfExists(t.resolve("o.txt")) + " " + Files.deleteIfExists(t.resolve("o.txt")));
        show("delete non-empty", () -> {
            Files.delete(t.resolve("a"));
            return "deleted";
        });
        show("deleteIfExists non-empty", () -> Files.deleteIfExists(t.resolve("a")));
        // listing and walking
        Path l = t.resolve("list");
        Files.createDirectory(l);
        for (String name : new String[] { "b", "a", "c", "10", "9", "B", ".dot" }) {
            Files.createFile(l.resolve(name));
        }
        Files.createDirectories(l.resolve("dir/sub"));
        Files.createFile(l.resolve("dir/x"));
        show("list", () -> {
            try (Stream<Path> s = Files.list(l)) {
                return s.toList();
            }
        });
        show("list sorted", () -> {
            try (Stream<Path> s = Files.list(l)) {
                return s.sorted().toList();
            }
        });
        show("walk", () -> {
            try (Stream<Path> s = Files.walk(l)) {
                return s.toList();
            }
        });
        show("walk depth 1", () -> {
            try (Stream<Path> s = Files.walk(l, 1)) {
                return s.toList();
            }
        });
        show("walk of a file", () -> {
            try (Stream<Path> s = Files.walk(f)) {
                return s.toList();
            }
        });
        show("list missing", () -> Files.list(t.resolve("none")));
        show("list of a file", () -> Files.list(f));
        show("walk missing", () -> Files.walk(t.resolve("none")));
        show("walk negative", () -> Files.walk(l, -1));
        show("find", () -> {
            try (Stream<Path> s = Files.find(l, 5, (p, a) -> p.getFileName().toString().length() == 1)) {
                return s.sorted().toList();
            }
        });
        show("newDirectoryStream", () -> {
            try (var s = Files.newDirectoryStream(l.resolve("dir"))) {
                StringBuilder seen = new StringBuilder();
                for (Path p : s) {
                    seen.append(p).append(';');
                }
                return seen;
            }
        });
        show("isSameFile", () -> Files.isSameFile(f, Path.of("t/./f.txt")) + " " + Files.isSameFile(f, t));
        show("isReadable isWritable", () -> Files.isReadable(f) + " " + Files.isWritable(f) + " "
                + Files.isReadable(Path.of("locked.txt")) + " " + Files.isWritable(Path.of("ro")) + " "
                + Files.isExecutable(f) + " " + Files.isHidden(l.resolve(".dot")));
        show("mismatch", () -> Files.mismatch(f, f) + " " + Files.mismatch(f, t.resolve("b.bin")));
        show("lastModified", () -> Files.getLastModifiedTime(f).toMillis() > 1_600_000_000_000L);
        show("lastModified missing", () -> Files.getLastModifiedTime(t.resolve("none")));
        show("toRealPath", () -> Path.of("t/a/../f.txt").toRealPath().endsWith("t/f.txt"));
        show("toRealPath missing", () -> Path.of("t/none/..").toRealPath());
    }
}

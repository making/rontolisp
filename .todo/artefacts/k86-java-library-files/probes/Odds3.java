import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

// The empty path through Files, the texts of the file system's exceptions, the words of
// relativize's refusal of a path holding dots, and what a move and a copy refuse.
public class Odds3 {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            System.out.println(label + " = " + io.run());
        }
        catch (Exception e) {
            System.out.println(label + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Path empty = Path.of("");
        Files.writeString(Path.of("f.txt"), "x");
        Files.createDirectories(Path.of("d/e"));
        Files.writeString(Path.of("d/g.txt"), "g");
        show("exists", () -> Files.exists(empty) + " " + Files.isDirectory(empty) + " " + Files.isRegularFile(empty));
        show("list", () -> {
            try (Stream<Path> s = Files.list(empty)) {
                return s.sorted().toList();
            }
        });
        show("walk", () -> {
            try (Stream<Path> s = Files.walk(empty, 1)) {
                return s.sorted().toList();
            }
        });
        show("readString", () -> Files.readString(empty));
        show("size", () -> Files.size(empty) > 0);
        show("toRealPath", () -> empty.toRealPath().equals(Path.of(System.getProperty("user.dir"))));
        show("list dot", () -> {
            try (Stream<Path> s = Files.list(Path.of("."))) {
                return s.sorted().toList();
            }
        });
        show("walk dir", () -> {
            try (Stream<Path> s = Files.walk(Path.of("d/"))) {
                return s.sorted().toList();
            }
        });
        System.out.println(new FileSystemException("a", "b", "r").getMessage() + "|" + new FileSystemException("a", null, "r").getMessage()
                + "|" + new FileSystemException(null, "b", "r").getMessage() + "|" + new FileSystemException("a").getMessage()
                + "|" + new FileSystemException(null, null, "r").getMessage() + "|" + new FileSystemException(null).getMessage()
                + "|" + new FileSystemException("a", "b", null).getMessage() + "|" + new NoSuchFileException("a", "b", "r").getMessage()
                + "|" + new AccessDeniedException("a").getReason() + "|" + new FileSystemException("a", "b", "r").getFile()
                + "|" + new FileSystemException("a", "b", "r").getOtherFile() + "|" + new FileSystemException("a", "b", "r").getReason());
        System.out.println(new InvalidPathException("in", "why").getMessage() + "|" + new InvalidPathException("in", "why", 3).getMessage()
                + "|" + new InvalidPathException("in", "why").getIndex() + "|" + new InvalidPathException("in", "why").getInput()
                + "|" + new InvalidPathException("in", "why").getReason());
        show("relativize dots", () -> Path.of("./../a").relativize(Path.of("b")));
        show("relativize dots 2", () -> Path.of("x/../../a").relativize(Path.of("b/./c")));
        show("move onto dir", () -> Files.move(Path.of("f.txt"), Path.of("d"), StandardCopyOption.REPLACE_EXISTING));
        show("copy onto dir", () -> Files.copy(Path.of("d/g.txt"), Path.of("d/e"), StandardCopyOption.REPLACE_EXISTING));
        show("copy onto self", () -> Files.copy(Path.of("d/g.txt"), Path.of("d/g.txt")));
        show("move onto self", () -> Files.move(Path.of("d/g.txt"), Path.of("d/./g.txt")));
        show("move dir", () -> Files.move(Path.of("d/e"), Path.of("d/e2")) + " " + Files.isDirectory(Path.of("d/e2")));
        show("copy into missing", () -> Files.copy(Path.of("d/g.txt"), Path.of("nope/g.txt")));
        show("move into missing", () -> Files.move(Path.of("d/g.txt"), Path.of("nope/g.txt")));
        show("isSymbolicLink", () -> Files.isSymbolicLink(Path.of("d")));
        show("createDirectory empty", () -> Files.createDirectory(empty));
        show("delete empty", () -> {
            Files.delete(empty);
            return "deleted";
        });
        show("newBufferedWriter empty", () -> Files.newBufferedWriter(empty));
    }
}

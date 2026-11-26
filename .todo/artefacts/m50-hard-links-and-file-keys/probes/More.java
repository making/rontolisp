import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

// isSameFile's and mismatch's refusals; createLink through a loop; a key's text where the
// file is one the program may not read.
public class More {
    interface Act {
        Object run() throws Exception;
    }

    static void show(String label, Act act) {
        try {
            System.out.println(label + ": " + act.run());
        } catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }

    public static void main(String[] args) throws Exception {
        Files.createLink(Path.of("lk.txt"), Path.of("locked.txt"));
        Files.createSymbolicLink(Path.of("loop"), Path.of("loop"));
        show("attrs under file", () -> Files.readAttributes(Path.of("a.txt/x"), BasicFileAttributes.class).size());
        show("same under file", () -> Files.isSameFile(Path.of("a.txt"), Path.of("a.txt/x")));
        show("same loop", () -> Files.isSameFile(Path.of("a.txt"), Path.of("loop")));
        show("same loop first", () -> Files.isSameFile(Path.of("loop"), Path.of("a.txt")));
        show("same loop under", () -> Files.isSameFile(Path.of("a.txt"), Path.of("loop/x")));
        show("same locked dir", () -> Files.isSameFile(Path.of("a.txt"), Path.of("lockd/x")));
        show("link loop", () -> Files.createLink(Path.of("z"), Path.of("loop/x")));
        show("link loop itself", () -> Files.createLink(Path.of("z"), Path.of("loop")));
        show("link into loop", () -> Files.createLink(Path.of("loop/z"), Path.of("a.txt")));
        show("link locked dir", () -> Files.createLink(Path.of("lockd/z"), Path.of("a.txt")));
        show("link from locked dir", () -> Files.createLink(Path.of("z2"), Path.of("lockd/x")));
        show("mismatch locked", () -> Files.mismatch(Path.of("locked.txt"), Path.of("lk.txt")));
        show("mismatch locked same", () -> Files.mismatch(Path.of("locked.txt"), Path.of("locked.txt")));
        show("mismatch a missing", () -> Files.mismatch(Path.of("a.txt"), Path.of("missing")));
        show("mismatch missing a", () -> Files.mismatch(Path.of("missing"), Path.of("a.txt")));
        show("mismatch locked a", () -> Files.mismatch(Path.of("locked.txt"), Path.of("a.txt")));
        show("mismatch dir", () -> Files.mismatch(Path.of("d"), Path.of("d/.")));
        show("long name", () -> Files.createLink(Path.of("n".repeat(300)), Path.of("a.txt")));
        show("key of locked", () -> Files.readAttributes(Path.of("locked.txt"), BasicFileAttributes.class).fileKey()
                .equals(Files.readAttributes(Path.of("lk.txt"), BasicFileAttributes.class).fileKey()));
        show("copy locked onto its link", () -> Files.copy(Path.of("locked.txt"), Path.of("lk.txt")));
    }
}

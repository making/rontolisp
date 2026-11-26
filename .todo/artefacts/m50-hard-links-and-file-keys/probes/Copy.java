import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// copy and move of a file the program may not read onto another name of it.
public class Copy {
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
        Files.createSymbolicLink(Path.of("slk"), Path.of("locked.txt"));
        Files.createLink(Path.of("b.txt"), Path.of("a.txt"));
        show("copy locked locked", () -> Files.copy(Path.of("locked.txt"), Path.of("locked.txt")));
        show("copy locked lk", () -> Files.copy(Path.of("locked.txt"), Path.of("lk.txt")));
        show("copy locked lk replace", () -> Files.copy(Path.of("locked.txt"), Path.of("lk.txt"), StandardCopyOption.REPLACE_EXISTING));
        show("copy slk locked", () -> Files.copy(Path.of("slk"), Path.of("locked.txt")));
        show("copy locked slk", () -> Files.copy(Path.of("locked.txt"), Path.of("slk")));
        show("copy slk locked nofollow", () -> Files.copy(Path.of("slk"), Path.of("locked.txt"), LinkOption.NOFOLLOW_LINKS));
        show("copy a b attrs", () -> Files.copy(Path.of("a.txt"), Path.of("b.txt"), StandardCopyOption.COPY_ATTRIBUTES));
        show("copy d d/.", () -> Files.copy(Path.of("d"), Path.of("d/.")));
        show("copy a missing-dir", () -> Files.copy(Path.of("a.txt"), Path.of("nodir/b")));
        show("move locked lk", () -> Files.move(Path.of("locked.txt"), Path.of("lk.txt")));
        show("move a b in ro", () -> Files.move(Path.of("a.txt"), Path.of("b.txt")));
        System.out.println("exists: " + Files.exists(Path.of("a.txt")) + " " + Files.exists(Path.of("b.txt"))
                + " " + Files.exists(Path.of("locked.txt")) + " " + Files.exists(Path.of("lk.txt")));
    }
}

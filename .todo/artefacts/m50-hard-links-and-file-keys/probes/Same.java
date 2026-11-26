import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// What copy, move and mismatch do with two names of one file.
public class Same {
    static void show(String label) throws Exception {
        System.out.println(label + ": a=" + Files.exists(Path.of("a.txt")) + " b=" + Files.exists(Path.of("b.txt"))
                + " nlink=" + Files.getAttribute(Path.of(Files.exists(Path.of("a.txt")) ? "a.txt" : "b.txt"), "unix:nlink"));
    }

    public static void main(String[] args) throws Exception {
        Path a = Path.of("a.txt");
        Path b = Path.of("b.txt");
        Files.writeString(a, "hello");
        Files.createLink(b, a);
        System.out.println("mismatch: " + Files.mismatch(a, b));
        try {
            System.out.println("copy: " + Files.copy(a, b));
        } catch (Exception e) {
            System.out.println("copy: " + e);
        }
        show("after copy");
        System.out.println("copy replace: " + Files.copy(a, b, StandardCopyOption.REPLACE_EXISTING));
        show("after copy replace");
        System.out.println("read b: " + Files.readString(b));
        try {
            System.out.println("move: " + Files.move(a, b));
        } catch (Exception e) {
            System.out.println("move: " + e);
        }
        show("after move");
        System.out.println("move replace: " + Files.move(a, b, StandardCopyOption.REPLACE_EXISTING));
        show("after move replace");
        System.out.println("move atomic: " + Files.move(a, b, StandardCopyOption.ATOMIC_MOVE));
        show("after move atomic");
        Files.createSymbolicLink(Path.of("s"), a);
        System.out.println("copy nofollow s b: " + Files.copy(Path.of("s"), b, LinkOption.NOFOLLOW_LINKS));
        System.out.println("b link: " + Files.isSymbolicLink(b));
        System.out.println("same s a: " + Files.isSameFile(Path.of("s"), a));
    }
}

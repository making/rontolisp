import java.nio.file.*;
import java.nio.file.attribute.*;
public class Eperm {
    public static void main(String[] a) {
        try {
            Files.setPosixFilePermissions(Path.of("/etc/hostname"), PosixFilePermissions.fromString("rw-r--r--"));
            System.out.println("set");
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

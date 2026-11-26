import java.nio.file.*;
import java.nio.file.attribute.*;
public class Bits {
    public static void main(String[] args) throws Exception {
        Path p = Files.writeString(Path.of("b.txt"), "b");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r-----"));
        System.out.println(PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));
    }
}

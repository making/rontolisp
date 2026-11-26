import java.nio.file.Files;
import java.nio.file.Path;

public class Hard {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("a.txt"), "x");
        Files.createLink(Path.of("b.txt"), Path.of("a.txt"));
        System.out.println(Files.isSameFile(Path.of("a.txt"), Path.of("b.txt")));
    }
}

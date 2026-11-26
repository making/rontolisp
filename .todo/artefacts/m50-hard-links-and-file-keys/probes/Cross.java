import java.nio.file.Files;
import java.nio.file.Path;

public class Cross {
    public static void main(String[] args) throws Exception {
        Path made = Files.createLink(Path.of(args[0] + "/o.txt"), Path.of("a.txt"));
        System.out.println(made + " " + Files.isSameFile(made, Path.of("a.txt")) + " " + Files.readString(made));
        Files.delete(made);
    }
}

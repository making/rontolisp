import java.nio.file.*;
import java.nio.file.attribute.*;
public class Owner {
    public static void main(String[] args) throws Exception {
        Path p = Files.writeString(Path.of("ow.txt"), "o");
        System.out.println(Files.getOwner(p).equals(Files.getOwner(Path.of("."))));
    }
}

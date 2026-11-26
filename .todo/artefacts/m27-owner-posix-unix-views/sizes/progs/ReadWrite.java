import java.nio.file.*;
public class ReadWrite {
    public static void main(String[] args) throws Exception {
        Path p = Path.of("rw.txt");
        Files.writeString(p, "hi");
        System.out.println(Files.readString(p) + " " + Files.size(p) + " " + Files.exists(p));
        Files.delete(p);
    }
}

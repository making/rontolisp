import java.nio.file.Files;
import java.nio.file.Path;

// The text of a file through Files.
public class ReadFile {
    public static void main(String[] args) throws Exception {
        System.out.println(Files.readString(Path.of("ReadFile.java")).length());
    }
}

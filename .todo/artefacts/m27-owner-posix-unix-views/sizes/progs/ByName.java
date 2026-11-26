import java.nio.file.*;
import java.nio.file.attribute.*;
public class ByName {
    public static void main(String[] args) throws Exception {
        Path p = Files.writeString(Path.of("n.txt"), "n");
        System.out.println(Files.readAttributes(p, "size,isRegularFile") + " " + Files.getAttribute(p, "size"));
        Files.setAttribute(p, "lastModifiedTime", FileTime.fromMillis(1000));
        System.out.println(Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime());
        System.out.println(Files.getFileAttributeView(p, BasicFileAttributeView.class).name());
    }
}

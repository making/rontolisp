import java.nio.file.Path;

// Path arithmetic and no file.
public class PathOnly {
    public static void main(String[] args) {
        Path p = Path.of("a/b").resolve("../c").normalize();
        System.out.println(p + " " + p.getFileName() + " " + Path.of("x/y").relativize(Path.of("x/z")));
    }
}

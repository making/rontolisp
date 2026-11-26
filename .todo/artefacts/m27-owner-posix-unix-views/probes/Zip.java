import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class Zip {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try { System.out.println(label + ": " + io.run()); } catch (Exception e) { System.out.println(label + ": " + e); }
    }
    public static void main(String[] a) throws Exception {
        Path zip = Path.of("z.zip").toAbsolutePath();
        Files.deleteIfExists(zip);
        try (FileSystem fs = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path f = fs.getPath("/f.txt");
            Files.writeString(f, "hi");
            Path none = fs.getPath("/none");
            UserPrincipal me = Files.getOwner(Path.of("."));
            show("views", () -> fs.supportedFileAttributeViews());
            show("getOwner", () -> Files.getOwner(f));
            show("getOwner missing", () -> Files.getOwner(none));
            show("setOwner", () -> Files.setOwner(f, me));
            show("setOwner null", () -> Files.setOwner(f, null));
            show("pfa", () -> Files.readAttributes(f, PosixFileAttributes.class));
            show("pfa missing", () -> Files.readAttributes(none, PosixFileAttributes.class));
            show("posix view", () -> Files.getFileAttributeView(f, PosixFileAttributeView.class));
            show("owner view", () -> Files.getFileAttributeView(f, FileOwnerAttributeView.class));
            show("posix:*", () -> Files.readAttributes(f, "posix:*"));
            show("unix:*", () -> Files.readAttributes(f, "unix:*"));
            show("owner:owner", () -> Files.getAttribute(f, "owner:owner"));
            show("set owner:owner", () -> Files.setAttribute(f, "owner:owner", me));
            show("perms", () -> Files.getPosixFilePermissions(f));
        }
    }
}

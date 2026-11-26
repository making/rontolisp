import java.nio.file.*;
import java.nio.file.attribute.*;

public class Foreign {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try { System.out.println(label + ": " + io.run()); } catch (Exception e) { System.out.println(label + ": " + e); }
    }
    public static void main(String[] a) throws Exception {
        Path f = Path.of("k.txt");
        Files.writeString(f, "k");
        UserPrincipal mine = () -> "someone";
        GroupPrincipal group = () -> "someone";
        show("setOwner foreign", () -> Files.setOwner(f, mine));
        show("setOwner foreign missing", () -> Files.setOwner(Path.of("none"), mine));
        show("setOwner foreign group", () -> Files.setOwner(f, group));
        show("set group foreign", () -> Files.setAttribute(f, "posix:group", group));
        show("eq foreign", () -> Files.getOwner(f).equals(mine));
        show("set group null", () -> Files.setAttribute(f, "posix:group", null));
        show("set uid null", () -> Files.setAttribute(f, "unix:uid", null));
        show("set size,", () -> Files.readAttributes(f, "posix:size,"));
    }
}

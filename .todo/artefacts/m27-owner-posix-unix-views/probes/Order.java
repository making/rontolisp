import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class Order {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try { System.out.println(label + ": " + io.run()); } catch (Exception e) { System.out.println(label + ": " + e); }
    }
    public static void main(String[] a) throws Exception {
        Path f = Path.of("h.txt");
        Files.deleteIfExists(f);
        Files.writeString(f, "hi");
        Path none = Path.of("none");
        UserPrincipal me = Files.getOwner(f);
        GroupPrincipal g = (GroupPrincipal) Files.getAttribute(f, "posix:group");
        show("setOwner null missing", () -> Files.setOwner(none, null));
        show("setOwner group missing", () -> Files.setOwner(none, g));
        show("set posix:owner group missing", () -> Files.setAttribute(none, "posix:owner", g));
        show("set posix:group user missing", () -> Files.setAttribute(none, "posix:group", me));
        show("set unix:uid long missing", () -> Files.setAttribute(none, "unix:uid", 1L));
        show("set unix:mode long missing", () -> Files.setAttribute(none, "unix:mode", 1L));
        show("set perms bad missing", () -> Files.setAttribute(none, "posix:permissions", "x"));
        show("view setGroup missing", () -> { Files.getFileAttributeView(none, PosixFileAttributeView.class).setGroup(g); return "ok"; });
        show("view setPerms missing", () -> { Files.getFileAttributeView(none, PosixFileAttributeView.class).setPermissions(Set.of()); return "ok"; });
        show("view setPerms null missing", () -> { Files.getFileAttributeView(none, PosixFileAttributeView.class).setPermissions(null); return "ok"; });
        show("view getOwner missing", () -> Files.getFileAttributeView(none, PosixFileAttributeView.class).getOwner());
        show("getOwner null", () -> Files.getOwner(null));
        show("getOwner null option", () -> Files.getOwner(f, (LinkOption) null));
        show("perm set wrong element", () -> { Set raw = new HashSet(); raw.add("x"); Files.setAttribute(f, "posix:permissions", raw); return "ok"; });
        show("perm set null element", () -> { Set<PosixFilePermission> raw = new HashSet<>(); raw.add(null); Files.setPosixFilePermissions(f, raw); return "ok"; });
        show("owner setOwner of view on group", () -> { Files.getFileAttributeView(f, FileOwnerAttributeView.class).setOwner(g); return "ok"; });
        show("owner hash eq", () -> me.hashCode() + " " + g.hashCode() + " " + me.equals(me) + " " + me.equals(null) + " " + me.equals("someone"));
        show("views set order", () -> new HashSet<>(List.of("basic", "owner", "posix", "unix")) + " " + new HashSet<>(List.of("owner", "dos", "basic", "posix", "user", "unix")));
        show("unix map keys order basic", () -> Files.readAttributes(f, "unix:lastAccessTime,lastModifiedTime,size,creationTime,isRegularFile,isDirectory,isSymbolicLink,isOther,fileKey").keySet());
        show("proc ns key", () -> Files.readAttributes(Path.of("/proc/self/ns/net"), BasicFileAttributes.class).fileKey() + " " + Files.readAttributes(Path.of("/proc/self/ns/net"), BasicFileAttributes.class).fileKey().hashCode() + " " + Files.getAttribute(Path.of("/proc/self/ns/net"), "unix:ino") + " " + Files.getAttribute(Path.of("/proc/self/ns/net"), "unix:dev"));
        show("dev null", () -> Files.readAttributes(Path.of("/dev/null"), "unix:rdev,dev,mode,nlink,isOther"));
        show("nobody", () -> Files.readAttributes(Path.of("/proc/1"), "posix:owner,group"));
    }
}

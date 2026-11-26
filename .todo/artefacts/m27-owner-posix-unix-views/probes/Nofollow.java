import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class Nofollow {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try { System.out.println(label + ": " + io.run()); } catch (Exception e) { System.out.println(label + ": " + e); }
    }
    static String mode(Path p) throws Exception { return Integer.toOctalString((Integer) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS)); }
    public static void main(String[] a) throws Exception {
        Path f = Path.of("g.txt");
        Files.deleteIfExists(f);
        Files.writeString(f, "hi");
        Files.setAttribute(f, "unix:mode", 0644);
        Path l = Path.of("gl");
        Files.deleteIfExists(l);
        Files.createSymbolicLink(l, f);
        Path dl = Path.of("gdl");
        Files.deleteIfExists(dl);
        Files.createSymbolicLink(dl, Path.of("nowhere"));
        UserPrincipal me = Files.getOwner(f);
        show("view perms nofollow", () -> { Files.getFileAttributeView(l, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setPermissions(PosixFilePermissions.fromString("rwx------")); return mode(f) + " " + mode(l); });
        show("set mode nofollow", () -> { Files.setAttribute(l, "unix:mode", 0640, LinkOption.NOFOLLOW_LINKS); return mode(f) + " " + mode(l); });
        show("set perms nofollow", () -> { Files.setAttribute(l, "posix:permissions", PosixFilePermissions.fromString("rw-------"), LinkOption.NOFOLLOW_LINKS); return mode(f) + " " + mode(l); });
        show("set perms nofollow dangling", () -> { Files.setAttribute(dl, "posix:permissions", PosixFilePermissions.fromString("rw-------"), LinkOption.NOFOLLOW_LINKS); return mode(dl); });
        show("set mode follow", () -> { Files.setAttribute(l, "unix:mode", 0604); return mode(f) + " " + mode(l); });
        show("set owner nofollow dangling", () -> Files.setAttribute(dl, "posix:owner", me, LinkOption.NOFOLLOW_LINKS));
        show("set uid nofollow dangling", () -> Files.setAttribute(dl, "unix:uid", 1000, LinkOption.NOFOLLOW_LINKS));
        show("set uid dangling", () -> Files.setAttribute(dl, "unix:uid", 1000));
        show("set owner dangling", () -> Files.setAttribute(dl, "posix:owner", me));
        show("owner:*,size", () -> Files.readAttributes(f, "owner:*,size"));
        show("owner:owner,foo", () -> Files.readAttributes(f, "owner:owner,foo"));
        show("owner:", () -> Files.readAttributes(f, "owner:"));
        show("posix:", () -> Files.readAttributes(f, "posix:"));
        show("unix:,size", () -> Files.readAttributes(f, "unix:,size"));
        show("posix:*,foo", () -> Files.readAttributes(f, "posix:*,foo"));
        show("posix:foo,*", () -> Files.readAttributes(f, "posix:foo,*"));
        show("owner:owner,owner", () -> Files.readAttributes(f, "owner:owner,owner"));
        show("getAttribute posix:*", () -> Files.getAttribute(f, "posix:*"));
        show("getAttribute owner:*", () -> Files.getAttribute(f, "owner:*"));
        show("getAttribute unix:a,b", () -> Files.getAttribute(f, "unix:a,b"));
        show("get posix:foo", () -> Files.getAttribute(f, "posix:foo"));
        show("get unix:foo", () -> Files.getAttribute(f, "unix:foo"));
        show("get owner:foo", () -> Files.getAttribute(f, "owner:foo"));
        show("get basic:foo", () -> Files.getAttribute(f, "basic:foo"));
        show("get posix:fileKey", () -> Files.getAttribute(f, "posix:fileKey") != null);
        show("set basic owner", () -> Files.setAttribute(f, "basic:owner", me));
        show("set unix:group", () -> Files.setAttribute(f, "unix:group", Files.getAttribute(f, "posix:group")));
        show("set unix:ctime", () -> Files.setAttribute(f, "unix:creationTime", FileTime.fromMillis(1)));
        show("set unix:uid long", () -> Files.setAttribute(f, "unix:uid", 1000L));
        show("set unix:mode missing name", () -> Files.setAttribute(f, "unix:", 1));
        show("set owner on missing view", () -> Files.setAttribute(f, "foo:owner", me));
        show("set gid locked", () -> { Path k = Path.of("locked.txt"); if (!Files.exists(k)) Files.writeString(k, "x"); Files.setAttribute(k, "unix:mode", 0); Files.setAttribute(k, "unix:gid", 1000); return mode(k); });
        show("owner map mutable", () -> { Map<String, Object> m = Files.readAttributes(f, "owner:*"); m.put("x", 1); return m.keySet(); });
        show("posix map mutable", () -> { Map<String, Object> m = Files.readAttributes(f, "posix:owner"); m.put("x", 1); return m.keySet(); });
        show("basic map mutable", () -> { Map<String, Object> m = Files.readAttributes(f, "size"); m.put("x", 1); return m.keySet(); });
    }
}

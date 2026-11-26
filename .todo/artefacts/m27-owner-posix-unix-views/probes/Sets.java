import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class Sets {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try {
            Object v = io.run();
            System.out.println(label + ": " + v);
        } catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }
    static String mode(Path p) throws Exception { return Integer.toOctalString((Integer) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS)); }
    public static void main(String[] a) throws Exception {
        Path f = Path.of("f.txt");
        Files.deleteIfExists(f);
        Files.writeString(f, "hi");
        Path l = Path.of("l");
        Files.deleteIfExists(l);
        Files.createSymbolicLink(l, Path.of("f.txt"));
        Path dl = Path.of("dl");
        Files.deleteIfExists(dl);
        Files.createSymbolicLink(dl, Path.of("nowhere"));
        Path locked = Path.of("locked.txt");
        if (!Files.exists(locked)) { Files.writeString(locked, "x"); }
        Files.setPosixFilePermissions(locked, EnumSet.noneOf(PosixFilePermission.class));
        UserPrincipal me = Files.getOwner(f);
        GroupPrincipal mine = (GroupPrincipal) Files.getAttribute(f, "posix:group");
        UserPrincipal root = Files.getOwner(Path.of("/"));
        GroupPrincipal rootg = (GroupPrincipal) Files.getAttribute(Path.of("/"), "posix:group");
        show("set mode", () -> { Files.setAttribute(f, "unix:mode", 0751); return mode(f); });
        show("set mode high", () -> { Files.setAttribute(f, "unix:mode", 0104751); return mode(f); });
        show("set mode setuid", () -> { Files.setAttribute(f, "unix:mode", 04751); return mode(f); });
        show("set mode long", () -> Files.setAttribute(f, "unix:mode", 0751L));
        show("set mode null", () -> Files.setAttribute(f, "unix:mode", null));
        show("set perms", () -> { Files.setAttribute(f, "posix:permissions", PosixFilePermissions.fromString("rw-r-----")); return mode(f); });
        show("set perms unix", () -> { Files.setAttribute(f, "unix:permissions", PosixFilePermissions.fromString("rw-r--r--")); return mode(f); });
        show("set perms null", () -> Files.setAttribute(f, "posix:permissions", null));
        show("set perms bad", () -> Files.setAttribute(f, "posix:permissions", "x"));
        show("set owner same", () -> Files.setAttribute(f, "posix:owner", me));
        show("set owner owner", () -> Files.setAttribute(f, "owner:owner", me));
        show("set owner unix", () -> Files.setAttribute(f, "unix:owner", me));
        show("set group same", () -> Files.setAttribute(f, "posix:group", mine));
        show("set owner root", () -> Files.setAttribute(f, "posix:owner", root));
        show("set group root", () -> Files.setAttribute(f, "posix:group", rootg));
        show("set owner group", () -> Files.setAttribute(f, "posix:owner", mine));
        show("set group user", () -> Files.setAttribute(f, "posix:group", me));
        show("set owner null", () -> Files.setAttribute(f, "posix:owner", null));
        show("set uid", () -> Files.setAttribute(f, "unix:uid", 1000));
        show("set uid root", () -> Files.setAttribute(f, "unix:uid", 0));
        show("set gid", () -> Files.setAttribute(f, "unix:gid", 1000));
        show("set gid 4", () -> { Files.setAttribute(f, "unix:gid", 4); return Files.getAttribute(f, "posix:group") + " " + Files.getAttribute(f, "unix:gid"); });
        show("set gid back", () -> { Files.setAttribute(f, "unix:gid", 1000); return Files.getAttribute(f, "posix:group"); });
        show("set ino", () -> Files.setAttribute(f, "unix:ino", 1L));
        show("set nlink", () -> Files.setAttribute(f, "unix:nlink", 1));
        show("set ctime", () -> Files.setAttribute(f, "unix:ctime", FileTime.fromMillis(0)));
        show("set unix lastModifiedTime", () -> { Files.setAttribute(f, "unix:lastModifiedTime", FileTime.fromMillis(5000)); return Files.getLastModifiedTime(f); });
        show("set posix lastModifiedTime", () -> { Files.setAttribute(f, "posix:lastModifiedTime", FileTime.fromMillis(6000)); return Files.getLastModifiedTime(f); });
        show("set posix size", () -> Files.setAttribute(f, "posix:size", 1L));
        show("set posix foo", () -> Files.setAttribute(f, "posix:foo", 1L));
        show("set owner size", () -> Files.setAttribute(f, "owner:size", 1L));
        show("set owner foo on missing", () -> Files.setAttribute(Path.of("none"), "owner:foo", 1L));
        show("set owner missing", () -> Files.setAttribute(Path.of("none"), "owner:owner", me));
        show("set mode missing", () -> Files.setAttribute(Path.of("none"), "unix:mode", 0644));
        show("set perms missing", () -> Files.setAttribute(Path.of("none"), "posix:permissions", PosixFilePermissions.fromString("rw-r--r--")));
        show("setOwner", () -> Files.setOwner(f, me));
        show("setOwner root", () -> Files.setOwner(f, root));
        show("setOwner null", () -> Files.setOwner(f, null));
        show("setOwner missing", () -> Files.setOwner(Path.of("none"), me));
        show("setOwner group", () -> Files.setOwner(f, mine));
        show("setOwner locked", () -> Files.setOwner(locked, me));
        show("getOwner locked", () -> Files.getOwner(locked));
        show("unix locked", () -> Files.readAttributes(locked, "unix:mode,nlink"));
        show("mode locked set", () -> { Files.setAttribute(locked, "unix:mode", 0); return mode(locked); });
        show("getOwner link nofollow", () -> Files.getOwner(dl, LinkOption.NOFOLLOW_LINKS));
        show("getOwner dangling", () -> Files.getOwner(dl));
        show("link mode", () -> mode(l) + " " + Files.readAttributes(l, "unix:mode,nlink", LinkOption.NOFOLLOW_LINKS) + " " + Files.readAttributes(l, "unix:mode,nlink"));
        show("link pfa", () -> PosixFilePermissions.toString(Files.readAttributes(l, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS).permissions()));
        show("link ino", () -> Files.getAttribute(l, "unix:ino").equals(Files.getAttribute(f, "unix:ino")) + " " + Files.getAttribute(l, "unix:ino", LinkOption.NOFOLLOW_LINKS).equals(Files.getAttribute(f, "unix:ino")));
        show("fileKey eq", () -> Files.readAttributes(l, BasicFileAttributes.class).fileKey().equals(Files.readAttributes(f, BasicFileAttributes.class).fileKey()) + " " + Files.readAttributes(l, BasicFileAttributes.class).fileKey().hashCode() + " " + Files.getAttribute(f, "unix:ino"));
        show("view setOwner nofollow", () -> { Files.getFileAttributeView(dl, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setOwner(me); return "ok"; });
        show("view setOwner dangling", () -> { Files.getFileAttributeView(dl, PosixFileAttributeView.class).setOwner(me); return "ok"; });
        show("view setPerms nofollow", () -> { Files.getFileAttributeView(l, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setPermissions(EnumSet.noneOf(PosixFilePermission.class)); return "ok"; });
        show("view setPerms null", () -> { Files.getFileAttributeView(f, PosixFileAttributeView.class).setPermissions(null); return "ok"; });
        show("view setOwner null", () -> { Files.getFileAttributeView(f, PosixFileAttributeView.class).setOwner(null); return "ok"; });
        show("view setGroup null", () -> { Files.getFileAttributeView(f, PosixFileAttributeView.class).setGroup(null); return "ok"; });
        show("view setGroup", () -> { Files.getFileAttributeView(f, PosixFileAttributeView.class).setGroup(mine); return "ok"; });
        show("view setTimes", () -> { Files.getFileAttributeView(f, PosixFileAttributeView.class).setTimes(FileTime.fromMillis(7000), null, null); return Files.getLastModifiedTime(f); });
        show("view owner setOwner missing", () -> { Files.getFileAttributeView(Path.of("none"), FileOwnerAttributeView.class).setOwner(me); return "ok"; });
        show("setPosixFilePermissions set mode", () -> { Files.setAttribute(f, "unix:mode", 04755); Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------")); return mode(f); });
        show("set perms keeps setuid", () -> { Files.setAttribute(f, "unix:mode", 04755); Files.setAttribute(f, "posix:permissions", PosixFilePermissions.fromString("rwx------")); return mode(f); });
        show("view perms keeps setuid", () -> { Files.setAttribute(f, "unix:mode", 04755); Files.getFileAttributeView(f, PosixFileAttributeView.class).setPermissions(PosixFilePermissions.fromString("rwx------")); return mode(f); });
        show("principal eq name", () -> me.equals(Files.getOwner(Path.of("/"))) + " " + root.getName() + " " + root.hashCode() + " " + rootg.hashCode());
        show("principal toString", () -> me.toString() + "|" + mine.toString() + "|" + mine.getName());
        show("views of missing", () -> Files.getFileAttributeView(Path.of("none"), PosixFileAttributeView.class).name());
        show("view equals", () -> Files.getFileAttributeView(f, PosixFileAttributeView.class).equals(Files.getFileAttributeView(f, PosixFileAttributeView.class)));
        show("readAttributes posixview class", () -> Files.getFileAttributeView(f, PosixFileAttributeView.class).readAttributes() instanceof PosixFileAttributes);
        show("readAttributes basic class", () -> Files.readAttributes(f, BasicFileAttributes.class) instanceof PosixFileAttributes);
        show("null class", () -> Files.readAttributes(f, (Class<BasicFileAttributes>) null));
        show("null view", () -> Files.getFileAttributeView(f, (Class<BasicFileAttributeView>) null));
        show("posix attributes nofollow dangling", () -> Files.readAttributes(dl, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS).owner());
        show("posix attributes dangling", () -> Files.readAttributes(dl, PosixFileAttributes.class).owner());
        show("loop", () -> { Path l1 = Path.of("loop1"); Files.deleteIfExists(l1); Files.createSymbolicLink(l1, l1); return Files.getOwner(l1); });
        show("under a file", () -> Files.getOwner(Path.of("f.txt/x")));
        show("under a file posix", () -> Files.readAttributes(Path.of("f.txt/x"), "posix:*"));
        show("dir in locked", () -> { Path d = Path.of("ld"); Files.createDirectories(d.resolve("x")); Files.setPosixFilePermissions(d, EnumSet.noneOf(PosixFilePermission.class)); try { return Files.getOwner(d.resolve("x")); } finally { Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------")); } });
    }
}

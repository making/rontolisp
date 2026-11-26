import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class Views {
    interface Io { Object run() throws Exception; }
    static void show(String label, Io io) {
        try {
            Object v = io.run();
            System.out.println(label + ": " + v + (v == null ? "" : " <" + v.getClass().getName() + ">"));
        } catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }
    static String types(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "null" : e.getValue().getClass().getName()).append(' ');
        }
        return sb.toString();
    }
    public static void main(String[] a) throws Exception {
        Path f = Path.of("f.txt");
        Files.writeString(f, "hi");
        Path d = Path.of("d");
        Files.createDirectories(d.resolve("sub"));
        show("posix keys", () -> Files.readAttributes(f, "posix:*").keySet() + " " + Files.readAttributes(f, "posix:*").getClass().getName());
        show("posix types", () -> types(Files.readAttributes(f, "posix:*")));
        show("unix keys", () -> Files.readAttributes(f, "unix:*").keySet());
        show("unix types", () -> types(Files.readAttributes(f, "unix:*")));
        show("owner keys", () -> Files.readAttributes(f, "owner:*"));
        show("posix map", () -> Files.readAttributes(f, "posix:*"));
        show("unix map", () -> Files.readAttributes(f, "unix:*"));
        show("posix named", () -> Files.readAttributes(f, "posix:owner,size,permissions"));
        show("posix named2", () -> Files.readAttributes(f, "posix:group,owner"));
        show("unix named", () -> Files.readAttributes(f, "unix:ino,nlink,mode,uid,gid"));
        show("unix basic+posix", () -> Files.readAttributes(f, "unix:size,permissions,owner,rdev,dev,ctime"));
        show("unix nlink dir", () -> Files.getAttribute(d, "unix:nlink"));
        show("unix mode", () -> Integer.toOctalString((Integer) Files.getAttribute(f, "unix:mode")));
        show("unix mode dir", () -> Integer.toOctalString((Integer) Files.getAttribute(d, "unix:mode")));
        show("unix rdev", () -> Files.getAttribute(f, "unix:rdev"));
        show("unix dev", () -> Files.getAttribute(f, "unix:dev"));
        show("unix ino", () -> Files.getAttribute(f, "unix:ino"));
        show("unix uid", () -> Files.getAttribute(f, "unix:uid"));
        show("unix gid", () -> Files.getAttribute(f, "unix:gid"));
        show("unix ctime", () -> Files.getAttribute(f, "unix:ctime"));
        show("posix owner", () -> Files.getAttribute(f, "posix:owner"));
        show("posix group", () -> Files.getAttribute(f, "posix:group"));
        show("owner owner", () -> Files.getAttribute(f, "owner:owner"));
        show("posix perms", () -> Files.getAttribute(f, "posix:permissions"));
        show("fileKey", () -> Files.readAttributes(f, BasicFileAttributes.class).fileKey());
        show("unix fileKey", () -> Files.getAttribute(f, "unix:fileKey"));
        show("posix bad", () -> Files.readAttributes(f, "posix:foo"));
        show("posix bad2", () -> Files.readAttributes(f, "posix:size,uid"));
        show("owner bad", () -> Files.readAttributes(f, "owner:size"));
        show("unix bad", () -> Files.getAttribute(f, "unix:foo"));
        show("owner bad get", () -> Files.getAttribute(f, "owner:group"));
        show("dos", () -> Files.readAttributes(f, "dos:*").keySet());
        show("user", () -> Files.readAttributes(f, "user:*"));
        show("posix missing", () -> Files.readAttributes(Path.of("none"), "posix:*"));
        show("posix missing bad name", () -> Files.readAttributes(Path.of("none"), "posix:foo"));
        show("getOwner", () -> Files.getOwner(f));
        show("getOwner missing", () -> Files.getOwner(Path.of("none")));
        show("owner name", () -> Files.getOwner(f).getName());
        show("owner eq", () -> Files.getOwner(f).equals(Files.getOwner(d)) + " " + (Files.getOwner(f).hashCode() == Files.getOwner(d).hashCode()));
        show("owner hash", () -> Files.getOwner(f).hashCode() + " " + Files.getOwner(f).getName().hashCode() + " " + Files.getAttribute(f, "unix:uid"));
        show("group hash", () -> Files.getAttribute(f, "posix:group").hashCode() + " " + Files.getAttribute(f, "posix:group").toString().hashCode() + " " + Files.getAttribute(f, "unix:gid"));
        show("owner vs group", () -> Files.getAttribute(f, "posix:group").equals(Files.getOwner(f)) + " " + Files.getOwner(f).equals(Files.getAttribute(f, "posix:group")));
        show("group is user", () -> (Files.getAttribute(f, "posix:group") instanceof GroupPrincipal) + " " + (Files.getOwner(f) instanceof GroupPrincipal));
        show("root owner", () -> Files.getOwner(Path.of("/")) + " " + Files.getAttribute(Path.of("/"), "posix:group"));
        show("pfa", () -> {
            PosixFileAttributes p = Files.readAttributes(f, PosixFileAttributes.class);
            return p.owner() + " " + p.group() + " " + PosixFilePermissions.toString(p.permissions()) + " " + p.size() + " " + p.isRegularFile() + " " + p.fileKey() + " " + p.getClass().getName();
        });
        show("pfa missing", () -> Files.readAttributes(Path.of("none"), PosixFileAttributes.class));
        show("dosattrs", () -> Files.readAttributes(f, DosFileAttributes.class));
        show("view posix", () -> {
            PosixFileAttributeView v = Files.getFileAttributeView(f, PosixFileAttributeView.class);
            return v.name() + " " + v.getOwner() + " " + v.readAttributes().group() + " " + v.getClass().getName();
        });
        show("view owner", () -> {
            FileOwnerAttributeView v = Files.getFileAttributeView(f, FileOwnerAttributeView.class);
            return v.name() + " " + v.getOwner() + " " + v.getClass().getName();
        });
        show("view missing", () -> {
            PosixFileAttributeView v = Files.getFileAttributeView(Path.of("none"), PosixFileAttributeView.class);
            return v == null ? "null" : v.readAttributes();
        });
        show("view owner missing", () -> Files.getFileAttributeView(Path.of("none"), FileOwnerAttributeView.class).getOwner());
        show("view dos", () -> Files.getFileAttributeView(f, DosFileAttributeView.class));
        show("view user", () -> Files.getFileAttributeView(f, UserDefinedFileAttributeView.class));
        show("views", () -> FileSystems.getDefault().supportedFileAttributeViews() + " " + FileSystems.getDefault().supportedFileAttributeViews().getClass().getName());
        show("views2", () -> f.getFileSystem().supportedFileAttributeViews() == FileSystems.getDefault().supportedFileAttributeViews());
    }
}

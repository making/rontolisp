import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

// What createLink, isSameFile and fileKey answer; run in a directory holding a.txt,
// locked.txt (mode 000), ro/ (mode 555), d/, sl -> a.txt, dang -> nothing.
public class Hard {
    static void link(String label, String link, String target) {
        try {
            Path made = Files.createLink(Path.of(link), Path.of(target));
            System.out.println(label + ": " + made + " link=" + Files.isSymbolicLink(made) + " same="
                    + Files.isSameFile(made, Path.of(target)));
        } catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }

    static void same(String label, String a, String b) {
        try {
            System.out.println(label + ": " + Files.isSameFile(Path.of(a), Path.of(b)));
        } catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }

    static Object key(String path, boolean follow) throws IOException {
        BasicFileAttributes attrs = follow ? Files.readAttributes(Path.of(path), BasicFileAttributes.class)
                : Files.readAttributes(Path.of(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.fileKey();
    }

    public static void main(String[] args) throws Exception {
        link("plain", "b.txt", "a.txt");
        Files.writeString(Path.of("b.txt"), "through b");
        System.out.println("read a: " + Files.readString(Path.of("a.txt")));
        System.out.println("nlink: " + Files.getAttribute(Path.of("a.txt"), "unix:nlink"));
        link("again", "b.txt", "a.txt");
        link("onto itself", "a.txt", "a.txt");
        link("missing target", "c.txt", "missing.txt");
        link("missing both", "missing.txt", "missing.txt");
        link("under ro", "ro/x.txt", "a.txt");
        link("dir", "e", "d");
        link("onto dir", "d", "a.txt");
        link("symlink", "f", "sl");
        link("dangling", "g", "dang");
        link("missing dir", "nodir/x.txt", "a.txt");
        link("under file", "a.txt/x", "a.txt");
        link("target under file", "h.txt", "a.txt/x");
        link("locked", "lk.txt", "locked.txt");
        link("into d", "d/a.txt", "a.txt");
        link("abs", Path.of("abs.txt").toAbsolutePath().toString(), "a.txt");
        link("dotdot", "d/../i.txt", "a.txt");
        link("empty link", "", "a.txt");
        link("empty target", "j.txt", "");
        link("slash target", "k.txt", "a.txt/");
        link("slash link", "l.txt/", "a.txt");
        link("onto symlink", "sl", "a.txt");
        link("onto dangling", "dang", "a.txt");
        link("tmp", "/dev/shm/hard-probe.txt", "a.txt");
        Files.deleteIfExists(Path.of("/dev/shm/hard-probe.txt"));
        try {
            Files.createLink(null, Path.of("a.txt"));
        } catch (Exception e) {
            System.out.println("null link: " + e.getClass().getName());
        }
        try {
            Files.createLink(Path.of("m.txt"), null);
        } catch (Exception e) {
            System.out.println("null target: " + e.getClass().getName());
        }
        same("same equal missing", "missing.txt", "missing.txt");
        same("same a missing", "a.txt", "missing.txt");
        same("same missing a", "missing.txt", "a.txt");
        same("same missing other", "missing.txt", "other.txt");
        same("same a b", "a.txt", "b.txt");
        same("same a ./a", "a.txt", "./a.txt");
        same("same sl a", "sl", "a.txt");
        same("same f a", "f", "a.txt");
        same("same a d", "a.txt", "d");
        same("same d d/.", "d", "d/.");
        same("same locked", "locked.txt", "lk.txt");
        same("same dang", "dang", "a.txt");
        same("same a dang", "a.txt", "dang");
        same("same under file", "a.txt", "a.txt/x");
        Object ka = key("a.txt", true);
        Object kb = key("b.txt", true);
        Object ks = key("sl", true);
        Object kn = key("sl", false);
        Object kd = key("d", true);
        System.out.println("key class: " + (ka == null ? null : ka.getClass().getName()));
        System.out.println("key a b equal: " + ka.equals(kb) + " " + (ka.hashCode() == kb.hashCode()));
        System.out.println("key a sl equal: " + ka.equals(ks));
        System.out.println("key a sl nofollow equal: " + ka.equals(kn));
        System.out.println("key a d equal: " + ka.equals(kd));
        System.out.println("key equals null: " + ka.equals(null) + " string: " + ka.equals(ka.toString()));
        System.out.println("key a same as read again: " + (ka == key("a.txt", true)));
        String text = ka.toString();
        System.out.println("key shape: " + text.replaceAll("[0-9a-f]+", "N"));
        long ino = (Long) Files.getAttribute(Path.of("a.txt"), "unix:ino");
        long dev = (Long) Files.getAttribute(Path.of("a.txt"), "unix:dev");
        System.out.println("key text: " + text.equals("(dev=" + Long.toHexString(dev) + ",ino=" + ino + ")"));
        System.out.println("key hash: " + (ka.hashCode() == (int) (dev + ino)));
        System.out.println("attr key: " + ka.equals(Files.getAttribute(Path.of("a.txt"), "fileKey")));
        System.out.println("map key: " + ka.equals(Files.readAttributes(Path.of("a.txt"), "*").get("fileKey")));
        System.out.println("unix map key: " + ka.equals(Files.readAttributes(Path.of("a.txt"), "unix:*").get("fileKey")));
        System.out.println("after delete a: " + kb.equals(key("b.txt", true)) + " nlink "
                + Files.getAttribute(Path.of("b.txt"), "unix:nlink"));
    }
}

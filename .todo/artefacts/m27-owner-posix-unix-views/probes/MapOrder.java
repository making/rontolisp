import java.nio.file.*;
import java.util.*;

public class MapOrder {
    static final String[] BASIC = { "lastAccessTime", "lastModifiedTime", "size", "creationTime", "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey" };
    public static void main(String[] a) throws Exception {
        Path f = Path.of("m.txt");
        Files.writeString(f, "hi");
        String[][] orders = {
            // candidate insertion orders: basic then posix then unix
            concat(BASIC, new String[] { "permissions", "owner", "group" }, new String[] { "mode", "ino", "dev", "rdev", "nlink", "uid", "gid", "ctime" }),
            concat(BASIC, new String[] { "owner", "permissions", "group" }, new String[] { "mode", "ino", "dev", "rdev", "nlink", "uid", "gid", "ctime" }),
        };
        String[] views = { "basic", "posix", "unix" };
        Random r = new Random(42);
        int[] fails = new int[orders.length];
        int tried = 0;
        for (int t = 0; t < 3000; t++) {
            String view = views[r.nextInt(3)];
            List<String> pool = new ArrayList<>(List.of(BASIC));
            if (!view.equals("basic")) pool.addAll(List.of("permissions", "owner", "group"));
            if (view.equals("unix")) pool.addAll(List.of("mode", "ino", "dev", "rdev", "nlink", "uid", "gid", "ctime"));
            Collections.shuffle(pool, r);
            List<String> asked = pool.subList(0, 1 + r.nextInt(pool.size()));
            String text = view + ":" + String.join(",", asked);
            List<String> jdk = new ArrayList<>(Files.readAttributes(f, text).keySet());
            tried++;
            for (int o = 0; o < orders.length; o++) {
                Map<String, Object> m = new HashMap<>();
                for (String n : orders[o]) if (asked.contains(n)) m.put(n, 1);
                if (!new ArrayList<>(m.keySet()).equals(jdk)) {
                    if (fails[o] < 3) System.out.println("order " + o + " differs for " + text + ": jdk " + jdk + " ours " + m.keySet());
                    fails[o]++;
                }
            }
        }
        System.out.println(tried + " " + Arrays.toString(fails));
        // the * texts
        for (String v : views) System.out.println(v + ":* " + Files.readAttributes(f, v + ":*").keySet());
    }
    static String[] concat(String[]... parts) {
        List<String> all = new ArrayList<>();
        for (String[] p : parts) all.addAll(List.of(p));
        return all.toArray(new String[0]);
    }
}

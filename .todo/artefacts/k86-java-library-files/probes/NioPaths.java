import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// A Path's arithmetic on the default file system: what each member answers, the
// working directory written as <cwd>.
public class NioPaths {
    static String cwd = Path.of("").toAbsolutePath().toString();

    interface Op {
        Object run() throws Exception;
    }

    static String show(Op op) {
        try {
            Object o = op.run();
            return o == null ? "null" : ("[" + o.toString().replace(cwd, "<cwd>") + "]");
        }
        catch (Exception e) {
            return "!" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static void path(String label, Path p) {
        List<String> names = new ArrayList<>();
        for (Path name : p) {
            names.add(name.toString());
        }
        System.out.println(label + " str=[" + p + "] fileName=" + show(p::getFileName) + " parent=" + show(p::getParent)
                + " root=" + show(p::getRoot) + " count=" + p.getNameCount() + " names=" + names + " abs="
                + p.isAbsolute() + " absPath=" + show(p::toAbsolutePath) + " normalize=" + show(p::normalize)
                + " hash=" + p.hashCode());
    }

    public static void main(String[] args) throws Exception {
        String[] texts = { "", "a", "a/", "a//b", "/", "//", "///a//b//", "a/./b", "a/../b", ".", "..", "/a", "a/b/c.txt",
                "./a", "../a", "a/b/.", "a/b/..", "/..", "/./", "a/../..", "../../a", "a/./../b/./c", "/a/../../b",
                ".a", "a.", " ", "a\\b", "x/y/z/", "a/b/../../c/." };
        for (String t : texts) {
            path("of(" + t + ")", Path.of(t));
        }
        // of several parts
        System.out.println(show(() -> Path.of("a", "b", "c")) + " " + show(() -> Path.of("", "a")) + " "
                + show(() -> Path.of("a", "", "b")) + " " + show(() -> Path.of("/", "a")) + " "
                + show(() -> Path.of("a", "/b")) + " " + show(() -> Path.of("a/", "/b/")) + " "
                + show(() -> Path.of("", "")) + " " + show(() -> Path.of("", "/")) + " "
                + show(() -> Paths.get("x", "y")) + " " + show(() -> Path.of("a", "..", "b")));
        System.out.println(show(() -> Path.of("a\u0000b")) + " " + show(() -> Path.of("a", "b\u0000")) + " "
                + show(() -> Path.of((String) null)) + " " + show(() -> Path.of("a", (String) null)));
        // getName, subpath
        Path abc = Path.of("/a/b/c");
        Path rel = Path.of("a/b/c");
        for (int i = -1; i <= 3; i++) {
            int j = i;
            System.out.println("getName(" + i + ") " + show(() -> abc.getName(j)) + " " + show(() -> rel.getName(j)));
        }
        for (int[] r : new int[][] { { 0, 1 }, { 0, 3 }, { 1, 3 }, { 2, 3 }, { 1, 1 }, { 3, 4 }, { -1, 2 }, { 2, 1 },
                { 0, 4 } }) {
            System.out.println("subpath(" + r[0] + "," + r[1] + ") " + show(() -> abc.subpath(r[0], r[1])) + " "
                    + show(() -> rel.subpath(r[0], r[1])));
        }
        System.out.println(show(() -> Path.of("").getName(0)) + " " + show(() -> Path.of("/").getName(0)) + " "
                + show(() -> Path.of("").subpath(0, 1)));
        // startsWith, endsWith
        String[][] pairs = { { "/a/b/c", "/a" }, { "/a/b/c", "/a/b" }, { "/a/b/c", "a" }, { "/a/b/c", "/" },
                { "a/b/c", "a" }, { "a/b/c", "a/b" }, { "a/b/c", "/a" }, { "a/b/c", "" }, { "", "" },
                { "a/b/c", "c" }, { "a/b/c", "b/c" }, { "/a/b/c", "/a/b/c" }, { "/a/b/c", "a/b/c" },
                { "a/bc", "a/b" }, { "a/b/c", "a/b/c/" }, { "a", "a" }, { "/", "/" }, { "a/b", "b/" } };
        for (String[] p : pairs) {
            Path x = Path.of(p[0]);
            Path y = Path.of(p[1]);
            System.out.println("[" + p[0] + "] [" + p[1] + "] starts=" + x.startsWith(y) + "/" + x.startsWith(p[1])
                    + " ends=" + x.endsWith(y) + "/" + x.endsWith(p[1]));
        }
        // resolve, resolveSibling
        String[] bases = { "", "a", "a/b", "/", "/a", "/a/b" };
        String[] others = { "", "c", "c/d", "/c", "..", "." };
        for (String b : bases) {
            StringBuilder line = new StringBuilder("resolve [" + b + "]");
            for (String o : others) {
                line.append(" ").append(show(() -> Path.of(b).resolve(o))).append(show(() -> Path.of(b).resolve(Path.of(o))))
                        .append("~").append(show(() -> Path.of(b).resolveSibling(o)));
            }
            System.out.println(line);
        }
        // relativize
        String[][] rels = { { "a", "a/b" }, { "a/b", "a" }, { "a/b", "a/c" }, { "a", "b" }, { "", "a" }, { "a", "" },
                { "", "" }, { "/a", "/a/b/c" }, { "/a/b", "/c" }, { "/", "/a" }, { "/a", "/" }, { "a", "/a" },
                { "/a", "a" }, { "a/./b", "a/c" }, { "a/../b", "c" }, { "../a", "b" }, { "a", "../b" },
                { "a/b", "a/b" }, { ".", "a" }, { "a", "." }, { "..", "a" }, { "x/y/z", "x/q" } };
        for (String[] r : rels) {
            System.out.println("relativize [" + r[0] + "] [" + r[1] + "] " + show(() -> Path.of(r[0]).relativize(Path.of(r[1]))));
        }
        // equality, order, text
        Path[] some = { Path.of("a"), Path.of("a/"), Path.of("b"), Path.of("A"), Path.of("/a"), Path.of(""), Path.of("a/b"),
                Path.of("a-b") };
        for (Path x : some) {
            StringBuilder line = new StringBuilder("[" + x + "]");
            for (Path y : some) {
                line.append(" ").append(x.equals(y) ? "=" : "!").append(Integer.signum(x.compareTo(y)));
            }
            System.out.println(line);
        }
        System.out.println(Path.of("a").equals("a") + " " + Path.of("a").equals(null) + " " + Path.of("a/b").hashCode()
                + " " + "a/b".hashCode());
        System.out.println(show(() -> Path.of("a").toFile()) + " " + show(() -> Path.of("").toFile()));
        System.out.println(show(() -> Path.of("a/b").startsWith((String) null)));
        System.out.println(show(() -> Path.of("a").resolve((String) null)));
        System.out.println(show(() -> Path.of("x").toRealPath()) + " " + show(() -> Path.of("").toRealPath()) + " "
                + show(() -> Path.of(".").toRealPath()));
    }
}

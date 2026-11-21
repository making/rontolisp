import java.io.File;

// A File's path arithmetic: what each member answers of a path, its working directory
// written as <cwd>.
public class FilePaths {
    static String cwd = new File("").getAbsolutePath();

    static String shown(Object o) {
        if (o == null) {
            return "null";
        }
        String s = o.toString();
        return s.replace(cwd, "<cwd>");
    }

    static void file(String label, File f) {
        System.out.println(label + " path=[" + f.getPath() + "] name=[" + f.getName() + "] parent=["
                + f.getParent() + "] abs=" + f.isAbsolute() + " absPath=[" + shown(f.getAbsolutePath())
                + "] str=[" + f + "] hash=" + f.hashCode() + " parentFile=[" + shown(f.getParentFile()) + "]");
    }

    public static void main(String[] args) throws Exception {
        String[] paths = { "", "a", "a/", "a//b", "/", "//", "///a//b//", "a/./b", "a/../b", ".", "..", "/a",
                "a/b/c.txt", ".hidden", "a.b.c", " a ", "a\\b", "~", "a/b/", "/..", "/./", "./a", "../a", "a/b/.",
                "a/b/..", "a/", "/a/", "//a", "a///", "x/y/z/" };
        for (String p : paths) {
            file("File(" + p + ")", new File(p));
        }
        String[] parents = { null, "", "/", "a", "a/", "/a", "//", "a//" };
        String[] children = { "", "b", "/b", "b/", "//b", "b//c", "." };
        for (String parent : parents) {
            for (String child : children) {
                file("File(" + parent + "," + child + ")", new File(parent, child));
            }
        }
        for (String parent : new String[] { "", "a", "/" }) {
            for (String child : children) {
                file("File(File(" + parent + ")," + child + ")", new File(new File(parent), child));
            }
        }
        file("File((File)null,b)", new File((File) null, "b"));
        try {
            new File((String) null);
        }
        catch (NullPointerException e) {
            System.out.println("File(null) " + e.getClass().getName() + " " + e.getMessage());
        }
        try {
            new File("a", (String) null);
        }
        catch (NullPointerException e) {
            System.out.println("File(a,null) " + e.getClass().getName() + " " + e.getMessage());
        }
        // equality and order are the path's
        File[] some = { new File("a"), new File("a/"), new File("b"), new File("A"), new File("/a"), new File(""),
                new File("a/b") };
        for (File x : some) {
            StringBuilder line = new StringBuilder("[" + x + "]");
            for (File y : some) {
                line.append(" ").append(x.equals(y) ? "=" : "!").append(Integer.signum(x.compareTo(y)));
            }
            System.out.println(line);
        }
        System.out.println(new File("a").equals("a") + " " + new File("a").equals(null));
        System.out.println(File.separator + " " + File.separatorChar + " " + File.pathSeparator + " "
                + File.pathSeparatorChar);
        // to a Path and back
        for (String p : new String[] { "", "a", "a//b/", "/", "/a/b/", "./a", "a/.." }) {
            System.out.println("toPath(" + p + ")=[" + new File(p).toPath() + "] toFile=[" + new File(p).toPath().toFile()
                    + "]");
        }
        try {
            new File("a\u0000b").toPath();
        }
        catch (Exception e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
        File nul = new File("a\u0000b");
        System.out.println("nul " + nul.exists() + " " + nul.length() + " " + nul.isFile() + " " + nul.getName().length()
                + " " + nul.lastModified());
        try {
            nul.getCanonicalPath();
        }
        catch (Exception e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
        // canonical paths resolve . and .. (and links) against the working directory
        for (String p : new String[] { "", ".", "a", "a/./b", "a/../b", "../x", "/a/../b", "/..", "/../..", "a/b/../..",
                "a/b/../../..", "/a/./b/", "//a//b" }) {
            System.out.println("canonical(" + p + ")=[" + shown(new File(p).getCanonicalPath()) + "] absoluteFile=["
                    + shown(new File(p).getAbsoluteFile()) + "]");
        }
    }
}

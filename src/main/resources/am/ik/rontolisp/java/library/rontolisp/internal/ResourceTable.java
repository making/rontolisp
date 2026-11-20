// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

// The files a resource name computed where the program runs may find, which the program
// carries (.kb/java-frontend.md, "Resources"): those of its source tree, which the
// program's loader looks in first, a name as it is written, and those of each root of its
// source path in order, a directory's by the name with its '.' and '..' resolved, a jar's
// by the name as it is. The lowering writes them as one text, the tree first: for each
// place a letter (T the tree, D a directory, J a jar), its count of files, a ';', the
// length of its path where the program was compiled, a ';' and the path, then for each
// file the length of its name, a ';', the name, its count of octets, a ';' and the
// octets, one character an octet. A directory is a file whose name ends in '/' (a
// directory's top is the empty name), its octets what it answers as a resource. A lookup walks the text: what a program carries is read
// where it is asked, and no table is made of it. A loader's reach is how far it looks:
// 0 nowhere, 1 the roots, 2 the tree and then the roots.
public final class ResourceTable {

    private static final String TABLE = table0();

    private ResourceTable() {
    }

    // beside a class: one leading '/' taken away, else the directory of the class's
    // package before the name
    public static InputStream beside(int reach, String directory, String name) {
        return find(reach, name.startsWith("/") ? name.substring(1) : directory + name);
    }

    public static URL besideUrl(int reach, String directory, String name) {
        return findUrl(reach, name.startsWith("/") ? name.substring(1) : directory + name);
    }

    // a loader's lookup: a name starting with '/' is none. The walk of search below,
    // written again without what a URL needs: a program reading a stream carries less
    public static InputStream find(int reach, String name) {
        if (reach == 0 || name.startsWith("/")) {
            return null;
        }
        String below = null;
        boolean resolved = false;
        int at = 0;
        while (at < TABLE.length()) {
            char kind = TABLE.charAt(at);
            int end = TABLE.indexOf(';', at + 1);
            int count = number(at + 1, end);
            at = end + 1;
            end = TABLE.indexOf(';', at);
            at = end + 1 + number(at, end);
            String key = name;
            if (kind == 'D') {
                if (!resolved) {
                    below = below(name);
                    resolved = true;
                }
                key = below;
            }
            boolean asked = key != null && (kind != 'T' || reach == 2);
            for (int i = 0; i < count; i++) {
                end = TABLE.indexOf(';', at);
                int length = number(at, end);
                int named = end + 1;
                at = named + length;
                end = TABLE.indexOf(';', at);
                int size = number(at, end);
                at = end + 1 + size;
                if (asked && matches(key, named, length)) {
                    return Resources.stream(TABLE.substring(end + 1, at));
                }
            }
        }
        return null;
    }

    public static URL findUrl(int reach, String name) {
        int[] found = new int[4];
        return search(reach, name, 0, found) < 0 ? null : located(found, name);
    }

    // the URL of every place holding the name, in the table's order
    public static Enumeration<URL> findAll(int reach, String name) {
        ArrayList<URL> every = new ArrayList<>();
        int[] found = new int[4];
        for (int at = search(reach, name, 0, found); at >= 0; at = search(reach, name, at, found)) {
            every.add(located(found, name));
        }
        return Collections.enumeration(every);
    }

    // the URL of a file found: its place's kind and path, the name as the place reads it;
    // a directory of the tree named with its slash, a name below a directory with the
    // slash it ends in or that a last '.' or '..' leaves
    private static URL located(int[] found, String name) {
        char kind = TABLE.charAt(found[0]);
        int end = TABLE.indexOf(';', found[0] + 1);
        int length = TABLE.indexOf(';', end + 1);
        String place = TABLE.substring(length + 1, length + 1 + number(end + 1, length));
        String named = name;
        if (kind == 'D') {
            named = below(name);
            if (!named.isEmpty() && (name.endsWith("/") || name.endsWith("/.") || name.endsWith("/.."))) {
                named = named + "/";
            }
        }
        else if (kind == 'T' && found[3] == 1 && !name.endsWith("/")) {
            named = name + "/";
        }
        return located0(kind == 'T' ? 0 : kind == 'D' ? 1 : 2, place, named, TABLE.substring(found[1], found[2]));
    }

    // whether a name of the table is the one asked: the name itself, or a directory's,
    // which ends in a '/' the name asked need not
    private static boolean matches(String key, int named, int length) {
        return TABLE.startsWith(key, named) && (length == key.length()
                || length == key.length() + 1 && TABLE.charAt(named + key.length()) == '/');
    }

    // the first file of the name a place from a point of the table on holds: where its
    // place starts, where its octets start and end, and whether it is a directory;
    // answers where the next place starts, or -1 where none holds it
    private static int search(int reach, String name, int from, int[] found) {
        if (reach == 0 || name.startsWith("/")) {
            return -1;
        }
        String below = null;
        boolean resolved = false;
        int at = from;
        while (at < TABLE.length()) {
            int placed = at;
            char kind = TABLE.charAt(at);
            int end = TABLE.indexOf(';', at + 1);
            int count = number(at + 1, end);
            at = end + 1;
            end = TABLE.indexOf(';', at);
            at = end + 1 + number(at, end);
            String key = name;
            if (kind == 'D') {
                if (!resolved) {
                    below = below(name);
                    resolved = true;
                }
                key = below;
            }
            boolean asked = key != null && (kind != 'T' || reach == 2);
            boolean held = false;
            for (int i = 0; i < count; i++) {
                end = TABLE.indexOf(';', at);
                int length = number(at, end);
                int named = end + 1;
                at = named + length;
                end = TABLE.indexOf(';', at);
                int size = number(at, end);
                at = end + 1 + size;
                if (asked && !held && matches(key, named, length)) {
                    found[0] = placed;
                    found[1] = end + 1;
                    found[2] = at;
                    found[3] = length == 0 || TABLE.charAt(named + length - 1) == '/' ? 1 : 0;
                    held = true;
                }
            }
            if (held) {
                return at;
            }
        }
        return -1;
    }

    // the decimal digits between two places of the table
    private static int number(int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * 10 + (TABLE.charAt(i) - '0');
        }
        return value;
    }

    // a name below the top of a directory: its '.' and empty segments dropped and a '..'
    // taking the segment before it away, or null where a '..' climbs above the top
    private static String below(String name) {
        String path = "";
        int start = 0;
        while (start <= name.length()) {
            int slash = name.indexOf('/', start);
            int end = slash < 0 ? name.length() : slash;
            String segment = name.substring(start, end);
            if (segment.equals("..")) {
                if (path.isEmpty()) {
                    return null;
                }
                int cut = path.lastIndexOf('/');
                path = cut < 0 ? "" : path.substring(0, cut);
            }
            else if (!segment.isEmpty() && !segment.equals(".")) {
                path = path.isEmpty() ? segment : path + "/" + segment;
            }
            start = end + 1;
        }
        return path;
    }

    private static native String table0();

    // java.net.URL's maker of a resource's URL
    private static native URL located0(int kind, String place, String name, String octets);
}

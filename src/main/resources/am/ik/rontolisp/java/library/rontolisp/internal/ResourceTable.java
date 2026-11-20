// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.InputStream;

// The files a resource name computed where the program runs may find, which the program
// carries (.kb/java-frontend.md, "Resources"): those of its source tree, which the
// program's loader looks in first, a name as it is written, and those of each root of its
// source path in order, a directory's by the name with its '.' and '..' resolved, a jar's
// by the name as it is. The lowering writes them as one text, the tree first: for each
// place a letter (T the tree, D a directory, J a jar), its count of files and a ';', then
// for each file the length of its name, a ';', the name, its count of octets, a ';' and
// the octets, one character an octet. A lookup walks the text: what a program carries is
// read where it is asked, and no table is made of it.
public final class ResourceTable {

    private static final String TABLE = table0();

    private ResourceTable() {
    }

    // beside a class: one leading '/' taken away, else the directory of the class's
    // package before the name
    public static InputStream beside(boolean tree, String directory, String name) {
        return find(tree, name.startsWith("/") ? name.substring(1) : directory + name);
    }

    // a loader's lookup: a name starting with '/' is none
    public static InputStream find(boolean tree, String name) {
        if (name.startsWith("/")) {
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
            String key = name;
            if (kind == 'D') {
                if (!resolved) {
                    below = below(name);
                    resolved = true;
                }
                key = below;
            }
            boolean asked = key != null && (kind != 'T' || tree);
            for (int i = 0; i < count; i++) {
                end = TABLE.indexOf(';', at);
                int length = number(at, end);
                int named = end + 1;
                at = named + length;
                end = TABLE.indexOf(';', at);
                int size = number(at, end);
                at = end + 1 + size;
                if (asked && length == key.length() && TABLE.startsWith(key, named)) {
                    return Resources.stream(TABLE.substring(end + 1, at));
                }
            }
        }
        return null;
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
}

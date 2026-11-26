// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.List;

// How the lines of a file differ from those expected, as AssertJ 3.27's hasContent says it
// under a JDK: the runs of lines a longest common subsequence leaves out, each at the
// number of its line in the text expected, with what was expected there, what was
// there, or both.
public final class Delta {

    private Delta() {
    }

    // The lines of a text, a last empty line dropped as a file's lines are read.
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r\n|\r|\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    // The differences, or null where there is none.
    public static String between(List<String> actual, List<String> expected) {
        int n = actual.size();
        int m = expected.size();
        int[][] common = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = actual.get(i).equals(expected.get(j)) ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<String> deltas = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && actual.get(i).equals(expected.get(j))) {
                i++;
                j++;
                continue;
            }
            int line = j + 1;
            List<String> extra = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            while ((i < n || j < m) && !(i < n && j < m && actual.get(i).equals(expected.get(j)))) {
                if (j < m && (i == n || common[i][j + 1] >= common[i + 1][j])) {
                    missing.add(expected.get(j++));
                }
                else {
                    extra.add(actual.get(i++));
                }
            }
            if (extra.isEmpty()) {
                deltas.add("Missing content at line " + line + ":\n  " + listed(missing));
            }
            else if (missing.isEmpty()) {
                deltas.add("Extra content at line " + line + ":\n  " + listed(extra));
            }
            else {
                deltas.add("Changed content at line " + line + ":\nexpecting:\n  " + listed(missing) + "\nbut was:\n  "
                        + listed(extra));
            }
        }
        return deltas.isEmpty() ? null : String.join("\n\n", deltas);
    }

    private static String listed(List<String> lines) {
        StringBuilder text = new StringBuilder("[");
        for (int k = 0; k < lines.size(); k++) {
            text.append(k == 0 ? "" : ",\n   ").append('"').append(lines.get(k)).append('"');
        }
        return text.append(']').toString();
    }

    // Where two contents first differ, as a binary check says it, or null where they do not.
    public static String bytes(byte[] actual, byte[] expected) {
        int length = Math.max(actual.length, expected.length);
        for (int k = 0; k < length; k++) {
            if (k >= actual.length || k >= expected.length || actual[k] != expected[k]) {
                return "does not have expected binary content at offset " + k + ", expecting:\n  \"" + hex(expected, k)
                        + "\"\nbut was:\n  \"" + hex(actual, k) + "\"";
            }
        }
        return null;
    }

    private static String hex(byte[] content, int at) {
        return at < content.length ? "0x" + Integer.toHexString(content[at] & 0xFF).toUpperCase() : "EOF";
    }
}

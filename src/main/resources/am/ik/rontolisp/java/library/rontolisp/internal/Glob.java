// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

// A glob of FileSystem.getPathMatcher matched against a path's text: * is any run of
// characters but a slash, ** any run, ? one character but a slash, [..] one of a set
// (! first denies it, - between two characters a range, a - first or last itself) and
// never a slash, {..,..} one of the globs between its commas, \ the next character
// itself. What it refuses, and where, is a JDK's (read off its answers): a group in a
// group, a slash in a set, a range backwards or a - after a range, a set, group or
// escape the glob ends in.
public final class Glob {

    // the kinds of a part
    private static final int CHARACTER = 0;

    private static final int ANY = 1;

    private static final int NAME = 2;

    private static final int ALL = 3;

    private static final int SET = 4;

    private static final int GROUP = 5;

    private static final class Part {

        final int kind;

        final char character;

        // a set: its first and last characters in pairs
        final char[] ranges;

        final boolean denied;

        // a group: the globs between its commas
        final Part[][] choices;

        Part(int kind, char character, char[] ranges, boolean denied, Part[][] choices) {
            this.kind = kind;
            this.character = character;
            this.ranges = ranges;
            this.denied = denied;
            this.choices = choices;
        }

        boolean holds(char c) {
            boolean in = false;
            for (int i = 0; i < ranges.length && !in; i += 2) {
                in = c >= ranges[i] && c <= ranges[i + 1];
            }
            return in != denied;
        }
    }

    private final Part[] parts;

    private Glob(Part[] parts) {
        this.parts = parts;
    }

    private static PatternSyntaxException refused(String what, String glob, int index) {
        return new PatternSyntaxException(what, glob, index);
    }

    public static Glob of(String glob) {
        List<Part> top = new ArrayList<>();
        List<Part[]> choices = null;
        List<Part> choice = null;
        int n = glob.length();
        int i = 0;
        while (i < n) {
            char c = glob.charAt(i);
            List<Part> into = choice != null ? choice : top;
            if (c == '\\') {
                if (i + 1 == n) {
                    throw refused("No character to escape", glob, i);
                }
                into.add(new Part(CHARACTER, glob.charAt(i + 1), null, false, null));
                i += 2;
            }
            else if (c == '*') {
                boolean all = i + 1 < n && glob.charAt(i + 1) == '*';
                into.add(new Part(all ? ALL : ANY, c, null, false, null));
                i += all ? 2 : 1;
            }
            else if (c == '?') {
                into.add(new Part(NAME, c, null, false, null));
                i++;
            }
            else if (c == '[') {
                i = set(glob, i + 1, into);
            }
            else if (c == '{') {
                if (choice != null) {
                    throw refused("Cannot nest groups", glob, i);
                }
                choices = new ArrayList<>();
                choice = new ArrayList<>();
                i++;
            }
            else if (c == ',' && choice != null) {
                choices.add(choice.toArray(new Part[0]));
                choice = new ArrayList<>();
                i++;
            }
            else if (c == '}' && choice != null) {
                choices.add(choice.toArray(new Part[0]));
                top.add(new Part(GROUP, c, null, false, choices.toArray(new Part[0][])));
                choices = null;
                choice = null;
                i++;
            }
            else {
                into.add(new Part(CHARACTER, c, null, false, null));
                i++;
            }
        }
        if (choice != null) {
            throw refused("Missing '}", glob, n - 1);
        }
        return new Glob(top.toArray(new Part[0]));
    }

    // Reads a set from just after its [ and adds it: where the glob goes on.
    private static int set(String glob, int i, List<Part> into) {
        int n = glob.length();
        StringBuilder ranges = new StringBuilder();
        boolean denied = i < n && glob.charAt(i) == '!';
        if (denied) {
            i++;
        }
        if (i < n && glob.charAt(i) == '-') {
            ranges.append('-').append('-');
            i++;
        }
        if (i < n && glob.charAt(i) == ']') {
            // a set of nothing, which a JDK refuses in words of the expression it makes
            throw refused("Unclosed character class", glob, i);
        }
        // the character a - may start a range from, and where it is
        int from = -1;
        int at = -1;
        while (true) {
            if (i >= n) {
                throw refused("Missing ']", glob, n - 1);
            }
            char c = glob.charAt(i);
            if (c == ']') {
                into.add(new Part(SET, c, ranges.toString().toCharArray(), denied, null));
                return i + 1;
            }
            if (c == '/') {
                throw refused("Explicit 'name separator' in class", glob, i);
            }
            if (c == '-' && i + 1 < n && glob.charAt(i + 1) == ']' && from >= 0) {
                ranges.append('-').append('-');
                i++;
            }
            else if (c == '-') {
                if (from < 0) {
                    throw refused("Invalid range", glob, i);
                }
                if (i + 1 >= n) {
                    throw refused("Missing ']", glob, n);
                }
                char to = glob.charAt(i + 1);
                if (to < from) {
                    throw refused("Invalid range", glob, at);
                }
                ranges.setCharAt(ranges.length() - 1, to);
                from = -1;
                i += 2;
            }
            else {
                ranges.append(c).append(c);
                from = c;
                at = i;
                i++;
            }
        }
    }

    public boolean matches(String text) {
        return matches(parts, 0, null, 0, text, 0);
    }

    // Whether the parts from k, then those of after from its k, match the text from i.
    private static boolean matches(Part[] parts, int k, Part[] after, int afterK, String text, int i) {
        int n = text.length();
        while (k < parts.length) {
            Part part = parts[k];
            if (part.kind == ANY || part.kind == ALL) {
                for (int j = i;; j++) {
                    if (matches(parts, k + 1, after, afterK, text, j)) {
                        return true;
                    }
                    if (j >= n || (part.kind == ANY && text.charAt(j) == '/')) {
                        return false;
                    }
                }
            }
            if (part.kind == GROUP) {
                for (Part[] choice : part.choices) {
                    if (matches(choice, 0, parts, k + 1, text, i)) {
                        return true;
                    }
                }
                return false;
            }
            if (i >= n) {
                return false;
            }
            char c = text.charAt(i);
            boolean taken = part.kind == CHARACTER ? c == part.character
                    : c != '/' && (part.kind == NAME || part.holds(c));
            if (!taken) {
                return false;
            }
            i++;
            k++;
        }
        return after != null ? matches(after, afterK, null, 0, text, i) : i == n;
    }
}

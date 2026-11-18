// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.regex;

// The searches of one pattern over one input, in a row. The engine's matcher
// (eval/regex.lisp) holds the input, the last match and where the next find starts;
// positions count the characters of the input.
public final class Matcher implements MatchResult {

    private Pattern pattern;

    private String input;

    private Object engine;

    Matcher(Pattern pattern, String input) {
        this.pattern = pattern;
        this.input = input;
        this.engine = matcher0(pattern.program, input);
    }

    public Pattern pattern() {
        return pattern;
    }

    public boolean find() {
        return find0(engine);
    }

    public boolean find(int start) {
        if (start < 0 || start > input.length()) {
            throw new IndexOutOfBoundsException("Illegal start index");
        }
        return findFrom0(engine, start);
    }

    public boolean lookingAt() {
        return lookingAt0(engine);
    }

    public boolean matches() {
        return matches0(engine);
    }

    public Matcher reset() {
        reset0(engine);
        return this;
    }

    public Matcher reset(CharSequence input) {
        this.input = input.toString();
        this.engine = matcher0(pattern.program, this.input);
        return this;
    }

    public int groupCount() {
        return pattern.groupCount();
    }

    public int start() {
        return start(0);
    }

    public int start(int group) {
        return start0(engine, checked(group));
    }

    public int start(String name) {
        return start0(engine, named(name));
    }

    public int end() {
        return end(0);
    }

    public int end(int group) {
        return end0(engine, checked(group));
    }

    public int end(String name) {
        return end0(engine, named(name));
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        return group0(engine, checked(group));
    }

    public String group(String name) {
        return group0(engine, named(name));
    }

    public String replaceAll(String replacement) {
        return replaced(replacement, true);
    }

    public String replaceFirst(String replacement) {
        return replaced(replacement, false);
    }

    public static String quoteReplacement(String s) {
        StringBuilder quoted = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.toString();
    }

    public String toString() {
        return "java.util.regex.Matcher[pattern=" + pattern + " region=0," + input.length() + " lastmatch="
                + (matched0(engine) ? group0(engine, 0) : "") + "]";
    }

    private String replaced(String replacement, boolean all) {
        if (replacement == null) {
            throw new NullPointerException("replacement");
        }
        Object made = replace0(pattern.program, input, replacement, all);
        if (Pattern.refused0(made)) {
            if (Pattern.index0(made) == 1) {
                throw new IndexOutOfBoundsException(Pattern.description0(made));
            }
            throw new IllegalArgumentException(Pattern.description0(made));
        }
        reset0(engine);
        return text0(made);
    }

    private int checked(int group) {
        if (!matched0(engine)) {
            throw new IllegalStateException("No match found");
        }
        if (group < 0 || group > pattern.groupCount()) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
        return group;
    }

    private int named(String name) {
        if (name == null) {
            throw new NullPointerException("Group name");
        }
        if (!matched0(engine)) {
            throw new IllegalStateException("No match found");
        }
        int group = pattern.groupIndex(name);
        if (group < 0) {
            throw new IllegalArgumentException("No group with name <" + name + ">");
        }
        return group;
    }

    private static native Object matcher0(Object program, String input);

    private static native boolean find0(Object engine);

    private static native boolean findFrom0(Object engine, int start);

    private static native boolean lookingAt0(Object engine);

    private static native boolean matches0(Object engine);

    private static native void reset0(Object engine);

    private static native boolean matched0(Object engine);

    private static native int start0(Object engine, int group);

    private static native int end0(Object engine, int group);

    private static native String group0(Object engine, int group);

    private static native Object replace0(Object program, String input, String replacement, boolean all);

    private static native String text0(Object made);
}

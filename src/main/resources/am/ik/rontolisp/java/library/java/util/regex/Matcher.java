// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.regex;

import java.util.Objects;
import java.util.function.Function;

// The searches of one pattern over one input, in a row. The engine's matcher
// (eval/regex.lisp) holds the input, the last match and where the next find starts;
// positions count the characters of the input.
public final class Matcher implements MatchResult {

    private Pattern pattern;

    private String input;

    private Object engine;

    // the region the searches stay in
    private int from;

    private int to;

    private boolean anchoring = true;

    private boolean transparent;

    // where the input not yet appended by appendReplacement starts
    private int appended;

    // what appendReplacement throws once it has appended a piece cut short
    private IndexOutOfBoundsException cut;

    Matcher(Pattern pattern, String input) {
        this.pattern = pattern;
        this.input = input;
        this.engine = matcher0(pattern.program, input);
        this.to = input.length();
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
        reset();
        return findFrom0(engine, start);
    }

    public boolean lookingAt() {
        return lookingAt0(engine);
    }

    public boolean matches() {
        return matches0(engine);
    }

    // The region is the whole input again; what its bounds are stays.
    public Matcher reset() {
        reset0(engine);
        from = 0;
        to = input.length();
        appended = 0;
        return this;
    }

    public Matcher reset(CharSequence input) {
        this.input = input.toString();
        this.engine = matcher0(pattern.program, this.input);
        bounds0(engine, anchoring, transparent);
        return reset();
    }

    public Matcher region(int start, int end) {
        if (start < 0 || start > input.length()) {
            throw new IndexOutOfBoundsException("start");
        }
        if (end < 0 || end > input.length()) {
            throw new IndexOutOfBoundsException("end");
        }
        if (start > end) {
            throw new IndexOutOfBoundsException("start > end");
        }
        reset();
        region0(engine, start, end);
        from = start;
        to = end;
        return this;
    }

    public int regionStart() {
        return from;
    }

    public int regionEnd() {
        return to;
    }

    public boolean hasAnchoringBounds() {
        return anchoring;
    }

    public Matcher useAnchoringBounds(boolean b) {
        anchoring = b;
        bounds0(engine, anchoring, transparent);
        return this;
    }

    public boolean hasTransparentBounds() {
        return transparent;
    }

    public Matcher useTransparentBounds(boolean b) {
        transparent = b;
        bounds0(engine, anchoring, transparent);
        return this;
    }

    public boolean hitEnd() {
        return hitEnd0(engine);
    }

    public boolean requireEnd() {
        return requireEnd0(engine);
    }

    public boolean hasMatch() {
        return matched0(engine);
    }

    public MatchResult toMatchResult() {
        int groups = pattern.groupCount();
        boolean matched = matched0(engine);
        int[] starts = new int[groups + 1];
        int[] ends = new int[groups + 1];
        for (int group = 0; matched && group <= groups; group++) {
            starts[group] = start0(engine, group);
            ends[group] = end0(engine, group);
        }
        return new Snapshot(pattern, input, matched, starts, ends);
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

    public String replaceAll(Function<MatchResult, String> replacer) {
        return replaced(Objects.requireNonNull(replacer), true);
    }

    public String replaceFirst(Function<MatchResult, String> replacer) {
        return replaced(Objects.requireNonNull(replacer), false);
    }

    private String replaced(Function<MatchResult, String> replacer, boolean all) {
        reset();
        if (!find()) {
            return input;
        }
        StringBuilder replaced = new StringBuilder();
        do {
            appendReplacement(replaced, replacer.apply(this));
        }
        while (all && find());
        return appendTail(replaced).toString();
    }

    public Matcher appendReplacement(StringBuffer sb, String replacement) {
        sb.append(piece(replacement));
        return pieced();
    }

    public Matcher appendReplacement(StringBuilder sb, String replacement) {
        sb.append(piece(replacement));
        return pieced();
    }

    // The exception of a piece that was cut at a group the pattern does not have.
    private Matcher pieced() {
        if (cut != null) {
            IndexOutOfBoundsException thrown = cut;
            cut = null;
            throw thrown;
        }
        return this;
    }

    public StringBuffer appendTail(StringBuffer sb) {
        sb.append(input.substring(appended));
        return sb;
    }

    public StringBuilder appendTail(StringBuilder sb) {
        sb.append(input.substring(appended));
        return sb;
    }

    // What appendReplacement appends: the input between the last match appended and
    // this one, then the replacement with its group references filled in. Measured
    // against a JDK 25: a malformed replacement appends nothing, one naming a group
    // number the pattern does not have appends what stands before the reference and
    // moves nothing on.
    private String piece(String replacement) {
        if (!matched0(engine)) {
            throw new IllegalStateException("No match found");
        }
        if (replacement == null) {
            throw new NullPointerException("replacement");
        }
        Object made = expand0(engine, replacement);
        int start = start0(engine, 0);
        if (Pattern.refused0(made)) {
            if (Pattern.index0(made) == 1 && start >= appended) {
                cut = new IndexOutOfBoundsException(Pattern.description0(made));
                return input.substring(appended, start) + partial0(made);
            }
            if (Pattern.index0(made) == 1) {
                throw new IndexOutOfBoundsException(Pattern.description0(made));
            }
            throw new IllegalArgumentException(Pattern.description0(made));
        }
        if (start < appended) {
            // a match appended twice
            throw new IndexOutOfBoundsException(
                    "Range [" + appended + ", " + start + ") out of bounds for length " + input.length());
        }
        String piece = input.substring(appended, start) + text0(made);
        appended = end0(engine, 0);
        return piece;
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
        return "java.util.regex.Matcher[pattern=" + pattern + " region=" + from + "," + to + " lastmatch="
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
        reset();
        return text0(made);
    }

    // A match as it was when it was asked for: its bounds and the input they index.
    private static final class Snapshot implements MatchResult {

        private final Pattern pattern;

        private final String input;

        private final boolean matched;

        private final int[] starts;

        private final int[] ends;

        Snapshot(Pattern pattern, String input, boolean matched, int[] starts, int[] ends) {
            this.pattern = pattern;
            this.input = input;
            this.matched = matched;
            this.starts = starts;
            this.ends = ends;
        }

        private int checked(int group) {
            if (!matched) {
                throw new IllegalStateException("No match found");
            }
            if (group < 0 || group >= starts.length) {
                throw new IndexOutOfBoundsException("No group " + group);
            }
            return group;
        }

        private int named(String name) {
            if (name == null) {
                throw new NullPointerException("Group name");
            }
            if (!matched) {
                throw new IllegalStateException("No match found");
            }
            int group = pattern.groupIndex(name);
            if (group < 0) {
                throw new IllegalArgumentException("No group with name <" + name + ">");
            }
            return group;
        }

        public int start() {
            return start(0);
        }

        public int start(int group) {
            return starts[checked(group)];
        }

        public int start(String name) {
            return starts[named(name)];
        }

        public int end() {
            return end(0);
        }

        public int end(int group) {
            return ends[checked(group)];
        }

        public int end(String name) {
            return ends[named(name)];
        }

        public String group() {
            return group(0);
        }

        public String group(int group) {
            int start = starts[checked(group)];
            return start < 0 ? null : input.substring(start, ends[group]);
        }

        public String group(String name) {
            int group = named(name);
            return starts[group] < 0 ? null : input.substring(starts[group], ends[group]);
        }

        public int groupCount() {
            return starts.length - 1;
        }

        public boolean hasMatch() {
            return matched;
        }
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

    private static native void region0(Object engine, int start, int end);

    private static native void bounds0(Object engine, boolean anchoring, boolean transparent);

    private static native Object expand0(Object engine, String replacement);

    private static native String partial0(Object refusal);

    private static native boolean hitEnd0(Object engine);

    private static native boolean requireEnd0(Object engine);

    private static native boolean matched0(Object engine);

    private static native int start0(Object engine, int group);

    private static native int end0(Object engine, int group);

    private static native String group0(Object engine, int group);

    private static native Object replace0(Object program, String input, String replacement, boolean all);

    private static native String text0(Object made);
}

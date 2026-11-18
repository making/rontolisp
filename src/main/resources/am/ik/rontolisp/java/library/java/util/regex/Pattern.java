// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.regex;

import java.util.function.Predicate;

// A compiled pattern of the engine every front end shares (eval/regex.lisp, written
// from the syntax this class's documentation describes). The native members are the
// engine's; compile, matches and quote are lowered where they are called, so that a
// pattern written as a constant brings only the tables its text may need.
public final class Pattern {

    public static final int UNIX_LINES = 1;

    public static final int CASE_INSENSITIVE = 2;

    public static final int COMMENTS = 4;

    public static final int MULTILINE = 8;

    public static final int LITERAL = 16;

    public static final int DOTALL = 32;

    public static final int UNICODE_CASE = 64;

    public static final int CANON_EQ = 128;

    public static final int UNICODE_CHARACTER_CLASS = 256;

    private final String pattern;

    private final int flags;

    final Object program;

    private Pattern(String pattern, int flags, Object program) {
        this.pattern = pattern;
        this.flags = flags;
        this.program = program;
    }

    // What a call of compile is: the pattern, and the engine's providers of the Unicode
    // properties, of the word characters past ASCII and of the grapheme clusters, each
    // null where the pattern cannot name what it provides.
    static Pattern make(String regex, int flags, Object unicode, Object category, Object grapheme) {
        if (regex == null) {
            throw new NullPointerException();
        }
        Object made = compile0(regex, flags, unicode, category, grapheme);
        if (refused0(made)) {
            throw new PatternSyntaxException(description0(made), regex, index0(made));
        }
        return new Pattern(regex, flags, made);
    }

    static boolean matchesText(String regex, CharSequence input, Object unicode, Object category, Object grapheme) {
        return make(regex, 0, unicode, category, grapheme).matcher(input).matches();
    }

    static String replaceText(String input, String regex, String replacement, boolean all, Object unicode,
            Object category, Object grapheme) {
        Matcher matcher = make(regex, 0, unicode, category, grapheme).matcher(input);
        return all ? matcher.replaceAll(replacement) : matcher.replaceFirst(replacement);
    }

    static String[] splitText(String input, String regex, int limit, Object unicode, Object category,
            Object grapheme) {
        return make(regex, 0, unicode, category, grapheme).split(input, limit);
    }

    public static native Pattern compile(String regex);

    public static native Pattern compile(String regex, int flags);

    public static native boolean matches(String regex, CharSequence input);

    public static String quote(String s) {
        return quote0(s);
    }

    public String pattern() {
        return pattern;
    }

    public String toString() {
        return pattern;
    }

    public int flags() {
        return flags;
    }

    public Matcher matcher(CharSequence input) {
        return new Matcher(this, input.toString());
    }

    public Predicate<String> asPredicate() {
        return text -> matcher(text).find();
    }

    public Predicate<String> asMatchPredicate() {
        return text -> matcher(text).matches();
    }

    public String[] split(CharSequence input) {
        return split(input, 0);
    }

    public String[] split(CharSequence input, int limit) {
        return split0(program, input.toString(), limit);
    }

    int groupCount() {
        return groupCount0(program);
    }

    // The index of a named group, -1 for a name the pattern has no group of.
    int groupIndex(String name) {
        return groupIndex0(program, name);
    }

    private static native Object compile0(String regex, int flags, Object unicode, Object category, Object grapheme);

    static native boolean refused0(Object made);

    static native String description0(Object refusal);

    static native int index0(Object refusal);

    static native String message0(String description, int index, String regex);

    private static native String quote0(String s);

    private static native String[] split0(Object program, String input, int limit);

    private static native int groupCount0(Object program);

    private static native int groupIndex0(Object program, String name);
}

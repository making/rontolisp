// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A position in the text and the set of delimiters; a token is found from the position
// each time it is asked for, so hasMoreTokens and countTokens move nothing. Where a null
// set of delimiters is refused was read off a JDK's answers: hasMoreTokens and nextToken
// refuse it at once, countTokens only where it has a character to look at.
public class StringTokenizer implements Enumeration<Object> {

    private final String text;

    private String delimiters;

    private final boolean returnDelims;

    private int position;

    public StringTokenizer(String str, String delim, boolean returnDelims) {
        this.text = Objects.requireNonNull(str);
        this.delimiters = delim;
        this.returnDelims = returnDelims;
    }

    public StringTokenizer(String str, String delim) {
        this(str, delim, false);
    }

    public StringTokenizer(String str) {
        this(str, " \t\n\r\f", false);
    }

    private boolean delimiter(int c) {
        return delimiters.indexOf(c) >= 0;
    }

    // where the token at or after from starts
    private int start(int from) {
        if (!returnDelims) {
            while (from < text.length() && delimiter(text.charAt(from))) {
                from++;
            }
        }
        return from;
    }

    // where the token starting at from ends: a delimiter is one of its own when they are
    // tokens
    private int end(int from) {
        int at = from;
        while (at < text.length() && !delimiter(text.charAt(at))) {
            at++;
        }
        if (returnDelims && at == from && at < text.length()) {
            at++;
        }
        return at;
    }

    public boolean hasMoreTokens() {
        Objects.requireNonNull(delimiters);
        return start(position) < text.length();
    }

    public String nextToken() {
        Objects.requireNonNull(delimiters);
        int from = start(position);
        if (from >= text.length()) {
            throw new NoSuchElementException();
        }
        position = end(from);
        return text.substring(from, position);
    }

    // The delimiters are changed first and stay changed, whatever the token.
    public String nextToken(String delim) {
        delimiters = delim;
        return nextToken();
    }

    public boolean hasMoreElements() {
        return hasMoreTokens();
    }

    public Object nextElement() {
        return nextToken();
    }

    public int countTokens() {
        int count = 0;
        int at = position;
        while (at < text.length()) {
            at = start(at);
            if (at >= text.length()) {
                break;
            }
            at = end(at);
            count++;
        }
        return count;
    }
}

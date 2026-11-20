import java.util.*;

// StringTokenizer: tokens, delimiters returned, delimiters changed, counts and refusals.
public class Tokens {
    static String all(StringTokenizer t) {
        StringBuilder b = new StringBuilder();
        b.append(t.countTokens()).append(':');
        while (t.hasMoreTokens()) {
            b.append('<').append(t.nextToken()).append('>');
        }
        b.append(' ').append(t.countTokens()).append(' ').append(t.hasMoreElements());
        return b.toString();
    }

    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            Object v = r.call();
            System.out.println(what + ": " + v);
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        String[] texts = {"", " ", "a", " a ", "a b\tc\nd\re\ff", "  lead and trail  ", "a,,b,", ",a,", ",,", "x"};
        String[] delims = {" \t\n\r\f", ",", ", ", "", "ab"};
        for (String text : texts) {
            for (String delim : delims) {
                for (boolean ret : new boolean[] {false, true}) {
                    System.out.println("[" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "] [" + delim.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "] " + ret + " " + all(new StringTokenizer(text, delim, ret)).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f"));
                }
            }
        }
        System.out.println(all(new StringTokenizer("default\tdelims are\nthese")));
        System.out.println(all(new StringTokenizer("a=1;b=2", "=;")));

        StringTokenizer t = new StringTokenizer("a,b;c d", ",");
        System.out.println(t.nextToken() + " " + t.countTokens());
        System.out.println(t.nextToken(";") + " " + t.countTokens());
        System.out.println(t.nextToken() + " " + t.countTokens() + " " + t.hasMoreTokens());
        StringTokenizer u = new StringTokenizer("key = value ; next", "=", true);
        System.out.println(u.nextToken() + "|" + u.nextToken() + "|" + u.nextToken(";") + "|" + u.nextToken() + "|" + u.hasMoreTokens());
        StringTokenizer v = new StringTokenizer("a b c", " ", true);
        System.out.println(v.nextToken() + "|" + v.nextToken("b") + "|" + v.nextToken() + "|" + v.countTokens());
        StringTokenizer w = new StringTokenizer("one two");
        Object first = w.nextElement();
        System.out.println(first + " " + w.hasMoreElements() + " " + w.nextElement() + " " + w.hasMoreElements());
        tryIt("next at end", () -> new StringTokenizer("").nextToken());
        tryIt("element at end", () -> new StringTokenizer(" ").nextElement());
        tryIt("next(delim) at end", () -> new StringTokenizer("a", "a").nextToken("b"));
        tryIt("next(delim) all delims", () -> new StringTokenizer("abab", "x").nextToken("ab"));
        tryIt("null text", () -> new StringTokenizer(null));
        tryIt("null text 2", () -> new StringTokenizer(null, ","));
        tryIt("null delim hasMore empty", () -> new StringTokenizer("", null).hasMoreTokens());
        tryIt("null delim hasMore", () -> new StringTokenizer("abc", null).hasMoreTokens());
        tryIt("null delim next", () -> new StringTokenizer("abc", null).nextToken());
        tryIt("null delim count", () -> new StringTokenizer("abc", null).countTokens());
        tryIt("null delim count empty", () -> new StringTokenizer("", null).countTokens());
        tryIt("null delim next empty", () -> new StringTokenizer("", null).nextToken());
        tryIt("null delim ret hasMore", () -> new StringTokenizer("abc", null, true).hasMoreTokens());
        tryIt("next(null)", () -> new StringTokenizer("abc").nextToken(null));
        tryIt("next(null) empty", () -> new StringTokenizer("").nextToken(null));
        StringTokenizer x = new StringTokenizer("a b");
        tryIt("x next(null)", () -> x.nextToken(null));
        tryIt("x after next(null)", () -> x.nextToken());
        Iterator<Object> it = new StringTokenizer("p q r").asIterator();
        StringBuilder b = new StringBuilder();
        while (it.hasNext()) {
            b.append(it.next()).append('/');
        }
        System.out.println(b);
        Enumeration<Object> e = new StringTokenizer("e1 e2");
        System.out.println(e.nextElement() + " " + e.nextElement() + " " + e.hasMoreElements());
        StringTokenizer y = new StringTokenizer("a  b", " ", false);
        System.out.println(y.hasMoreTokens() + " " + y.hasMoreTokens() + " " + y.countTokens() + " " + y.nextToken() + " " + y.countTokens());
        StringTokenizer z = new StringTokenizer("a b", " ", true);
        System.out.println(z.nextToken("") + "|" + z.hasMoreTokens());
        StringTokenizer z2 = new StringTokenizer("a b c", " ");
        System.out.println(z2.nextToken() + "|" + z2.nextToken("") + "|" + z2.hasMoreTokens());
    }
}

import java.util.*;

// StringTokenizer: a null set of delimiters, and whether hasMoreTokens moves the position.
public class Tokens2 {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName());
        }
    }

    public static void main(String[] args) {
        tryIt("ret empty hasMore", () -> new StringTokenizer("", null, true).hasMoreTokens());
        tryIt("ret empty count", () -> new StringTokenizer("", null, true).countTokens());
        tryIt("ret empty next", () -> new StringTokenizer("", null, true).nextToken());
        tryIt("space count", () -> new StringTokenizer(" ", null).countTokens());
        tryIt("ret abc count", () -> new StringTokenizer("abc", null, true).countTokens());
        tryIt("ret abc next", () -> new StringTokenizer("abc", null, true).nextToken());
        tryIt("elements", () -> new StringTokenizer("abc", null).hasMoreElements());
        StringTokenizer t = new StringTokenizer("a,b;c", ",");
        System.out.println(t.nextToken() + " " + t.hasMoreTokens() + " " + t.nextToken(";") + " " + t.nextToken());
        StringTokenizer u = new StringTokenizer("a,b;c", ",");
        System.out.println(u.nextToken() + " " + u.countTokens() + " " + u.nextToken(";"));
        StringTokenizer v = new StringTokenizer(",,a;b", ",");
        System.out.println(v.hasMoreTokens() + " " + v.nextToken(";"));
        StringTokenizer w = new StringTokenizer(",,a;b", ",");
        System.out.println(w.hasMoreTokens() + " " + w.nextToken());
        tryIt("w next(;)", () -> w.nextToken(";"));
        StringTokenizer x = new StringTokenizer(";a,b", ";");
        System.out.println(x.hasMoreTokens() + " " + x.nextToken(",") + "|" + x.nextToken());
        StringTokenizer y = new StringTokenizer(" x y ", " ", true);
        System.out.println(y.hasMoreTokens() + "|" + y.nextToken() + "|" + y.nextToken("x") + "|" + y.nextToken() + "|" + y.countTokens());
        String sup = "a😀b😀c";
        StringTokenizer z = new StringTokenizer(sup, "😀");
        System.out.println(z.countTokens() + " " + z.nextToken() + " " + z.nextToken() + " " + z.nextToken());
        StringTokenizer z2 = new StringTokenizer(sup, "😀", true);
        System.out.println(z2.countTokens());
    }
}

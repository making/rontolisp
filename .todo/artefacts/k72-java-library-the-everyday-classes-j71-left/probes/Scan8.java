import java.util.*;

// Scanner: letters in the groups of a number of radix 16, and the delimiter's bounds.
public class Scan8 {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    static String tokens(Scanner sc) {
        StringBuilder b = new StringBuilder();
        while (sc.hasNext()) {
            b.append('<').append(sc.next()).append('>');
        }
        return b.toString();
    }

    public static void main(String[] args) {
        for (String t : new String[] {"1,abc", "1,0a0", "f,fff", "12,ABC", "1a,bcd", "1a2,bcd", "9,999,abc", "1,a,bcd", "1,ab", "1,abcd"}) {
            tryIt(t + " r16", () -> new Scanner(t).nextInt(16));
        }
        System.out.println(tokens(new Scanner(",a,b").useDelimiter("^,")));
        System.out.println(tokens(new Scanner("a,b,c").useDelimiter("(?<=a),")));
        System.out.println(tokens(new Scanner("ab cd ef").useDelimiter("\\b")));
        System.out.println(tokens(new Scanner("a1b2").useDelimiter("(?=\\d)")));
        System.out.println(tokens(new Scanner("aXbXXc").useDelimiter("X$|X")));
        System.out.println(tokens(new Scanner("a, b,c").useDelimiter(",\\s*")));
        System.out.println(tokens(new Scanner("1,ab").useDelimiter(",")));
        Scanner s = new Scanner("1,ab").useDelimiter(",");
        s.next();
        tryIt("lookbehind token", () -> s.hasNext("(?<=,)ab"));
        Scanner s2 = new Scanner("1,ab").useDelimiter(",");
        s2.next();
        tryIt("^ token", () -> s2.hasNext("^ab"));
        Scanner s3 = new Scanner("x ab y");
        s3.next();
        tryIt("\\b token", () -> s3.hasNext("\\bab\\b"));
        Scanner s4 = new Scanner("x ab y");
        s4.next();
        tryIt("$ token", () -> s4.hasNext("ab$"));
        Scanner s5 = new Scanner("ab");
        tryIt("^ first", () -> s5.hasNext("^ab$"));
        Scanner s6 = new Scanner("x ab");
        s6.next();
        tryIt("findInLine ^", () -> s6.findInLine("^ ab"));
        tryIt("skip ^", () -> new Scanner(" ab").skip("^ ").next());
        Scanner s7 = new Scanner("x ab");
        s7.next();
        tryIt("skip ^ after", () -> s7.skip("^ "));
        Scanner s8 = new Scanner("x ab");
        s8.next();
        tryIt("skip lookbehind", () -> s8.skip("(?<=x) ").next());
        Scanner s9 = new Scanner("x\nab");
        s9.nextLine();
        tryIt("findInLine ^ after line", () -> s9.findInLine("^ab"));
        Scanner s10 = new Scanner("x\nab");
        s10.nextLine();
        tryIt("findInLine (?m)^", () -> s10.findInLine("(?m)^ab"));
    }
}

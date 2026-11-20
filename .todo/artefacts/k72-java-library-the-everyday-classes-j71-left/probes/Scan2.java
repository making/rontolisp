import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

// Scanner: the words of a refused number, a float under another radix, finding and
// skipping by a pattern, the match each operation leaves, the scanner's text.
public class Scan2 {
    static String show(Object o) {
        if (o == null) {
            return "null";
        }
        String s = String.valueOf(o);
        return "<" + s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + ">";
    }

    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + show(r.call()));
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + (e instanceof NullPointerException ? "" : show(e.getMessage())));
        }
    }

    static String match(Scanner sc) {
        try {
            MatchResult m = sc.match();
            StringBuilder b = new StringBuilder("[" + m.start() + "," + m.end() + " " + show(m.group()) + " " + m.groupCount());
            for (int g = 1; g <= m.groupCount(); g++) {
                b.append(" ").append(show(m.group(g)));
            }
            return b.append("]").toString();
        } catch (IllegalStateException e) {
            return "[" + e.getMessage() + "]";
        }
    }

    public static void main(String[] args) {
        tryIt("int16 overflow", () -> new Scanner("80000000").nextInt(16));
        tryIt("int grouped overflow", () -> new Scanner("2,147,483,648").nextInt());
        tryIt("int arabic overflow", () -> new Scanner("\u0663\u0663\u0663\u0663\u0663\u0663\u0663\u0663\u0663\u0663").nextInt());
        tryIt("long overflow", () -> new Scanner("9223372036854775808").nextLong());
        tryIt("short", () -> new Scanner("32768").nextShort());
        tryIt("short16", () -> new Scanner("8000").nextShort(16));
        tryIt("byte -129", () -> new Scanner("-129").nextByte());
        tryIt("byte2", () -> new Scanner("10000000").nextByte(2));
        tryIt("float radix16 ff", () -> new Scanner("ff").useRadix(16).nextDouble());
        tryIt("float radix16 1.5", () -> new Scanner("1.5").useRadix(16).nextDouble());
        tryIt("float radix2 12", () -> new Scanner("12").useRadix(2).nextDouble());
        tryIt("float radix2 1,000", () -> new Scanner("1,000").useRadix(2).hasNextDouble());
        tryIt("exp arabic", () -> new Scanner("1e\u0663").nextDouble());
        tryIt("dec arabic", () -> new Scanner("\u0663.\u0664").nextDouble());
        tryIt("bigdec exp", () -> new Scanner("1.5e\u0663").nextBigDecimal());
        tryIt("big radix16", () -> new Scanner("-FFFFFFFFFFFFFFFFFFFF").nextBigInteger(16));
        tryIt("big grouped", () -> new Scanner("1,234,567,890,123,456,789").nextBigInteger());
        tryIt("big radix 3", () -> new Scanner("12").nextBigInteger(3));
        tryIt("big radix 3 bad", () -> new Scanner("13").nextBigInteger(3));
        tryIt("hex float", () -> new Scanner("0x1.0p-1074").nextDouble());
        tryIt("hex float big", () -> new Scanner("0X1.FFFFFFFFFFFFFP1023").nextDouble());
        tryIt("hex float f", () -> new Scanner("0x1.8").nextFloat());
        tryIt("dec int-ish", () -> new Scanner("-0").nextBigDecimal());
        tryIt("float -0.0", () -> new Scanner("-0.0").nextFloat());
        tryIt("double 1,000,000.5e-3", () -> new Scanner("1,000,000.5e-3").nextDouble());
        tryIt("double +.5", () -> new Scanner("+.5").nextDouble());
        tryIt("double -5.", () -> new Scanner("-5.").nextDouble());
        tryIt("double .", () -> new Scanner(".").hasNextDouble());
        tryIt("double e5", () -> new Scanner("e5").hasNextDouble());
        tryIt("double 1.2.3", () -> new Scanner("1.2.3").hasNextDouble());
        tryIt("double 1,000.5,5", () -> new Scanner("1,000.5,5").hasNextDouble());
        tryIt("double +-1", () -> new Scanner("+-1").hasNextDouble());
        tryIt("double 1e+-5", () -> new Scanner("1e+-5").hasNextDouble());
        tryIt("double 1e5.5", () -> new Scanner("1e5.5").hasNextDouble());
        tryIt("double NaN1", () -> new Scanner("NaN1").hasNextDouble());
        tryIt("double +NaN", () -> new Scanner("+NaN").nextDouble());
        tryIt("double -Infinity", () -> new Scanner("-Infinity").nextDouble());

        Scanner f = new Scanner("abc 123 def\nxyz 45");
        tryIt("findInLine \\d+", () -> f.findInLine("\\d+"));
        System.out.println(match(f));
        tryIt("next", () -> f.next());
        System.out.println(match(f));
        tryIt("findInLine x", () -> f.findInLine("x"));
        tryIt("findInLine (\\w)(\\w)", () -> f.findInLine("(\\w)(\\w)"));
        System.out.println(match(f));
        tryIt("nextLine", () -> f.nextLine());
        System.out.println(match(f));
        tryIt("findInLine 4", () -> f.findInLine("4"));
        tryIt("next", () -> f.next());
        tryIt("findInLine at end", () -> f.findInLine(".*"));
        Scanner g = new Scanner("\nabc");
        tryIt("findInLine at newline", () -> g.findInLine("abc"));
        tryIt("findInLine empty at newline", () -> g.findInLine(".*"));
        tryIt("then nextLine", () -> g.nextLine());
        tryIt("findInLine abc", () -> g.findInLine("abc"));
        Scanner h = new Scanner("one two three\nfour five");
        tryIt("horizon 0 five", () -> h.findWithinHorizon("f\\w+", 0));
        System.out.println(match(h));
        tryIt("next after", () -> h.next());
        Scanner h2 = new Scanner("one two three");
        tryIt("horizon 5 two", () -> h2.findWithinHorizon("two", 5));
        tryIt("horizon 7 two", () -> h2.findWithinHorizon("two", 7));
        tryIt("horizon 3 \\w+", () -> h2.findWithinHorizon("\\w+", 3));
        tryIt("horizon -1", () -> h2.findWithinHorizon("x", -1));
        tryIt("horizon \\b", () -> new Scanner("abcdef").findWithinHorizon("abc\\b", 3));
        tryIt("horizon $", () -> new Scanner("abcdef").findWithinHorizon("abc$", 3));
        tryIt("horizon lookahead", () -> new Scanner("abcdef").findWithinHorizon("abc(?=d)", 3));
        tryIt("horizon ^", () -> new Scanner(" abc").findWithinHorizon("^abc", 0));
        Scanner s = new Scanner("  abc def");
        tryIt("skip \\s*", () -> s.skip("\\s*"));
        System.out.println(match(s));
        tryIt("next", () -> s.next());
        tryIt("skip x", () -> s.skip("x"));
        tryIt("skip \\s+d", () -> s.skip("\\s+d"));
        tryIt("next", () -> s.next());
        tryIt("skip at end", () -> s.skip(""));
        tryIt("skip at end 2", () -> s.skip("a"));
        Scanner s2 = new Scanner("abc");
        tryIt("skip b (not at start)", () -> s2.skip("b"));
        tryIt("next", () -> s2.next());

        Scanner m = new Scanner("12 ab 3.5 true line one\nline two");
        tryIt("nextInt", () -> m.nextInt());
        System.out.println(match(m));
        tryIt("hasNextInt", () -> m.hasNextInt());
        System.out.println(match(m));
        tryIt("hasNext", () -> m.hasNext());
        System.out.println(match(m));
        tryIt("next", () -> m.next());
        System.out.println(match(m));
        tryIt("hasNextDouble", () -> m.hasNextDouble());
        System.out.println(match(m));
        tryIt("nextDouble", () -> m.nextDouble());
        System.out.println(match(m));
        tryIt("nextBoolean", () -> m.nextBoolean());
        System.out.println(match(m));
        tryIt("hasNextLine", () -> m.hasNextLine());
        System.out.println(match(m));
        tryIt("nextLine", () -> m.nextLine());
        System.out.println(match(m));
        tryIt("next(\\w+)", () -> m.next("l\\w+"));
        System.out.println(match(m));
        tryIt("next(\\d)", () -> m.next("\\d"));
        System.out.println(match(m));
        tryIt("hasNext(t.o)", () -> m.hasNext("t.o"));
        System.out.println(match(m));
        tryIt("next(Pattern)", () -> m.next(Pattern.compile("t(w)o")));
        System.out.println(match(m));
        tryIt("hasNextInt at end", () -> m.hasNextInt());
        System.out.println(match(m));
        tryIt("next at end", () -> m.next());
        System.out.println(match(m));

        System.out.println(new Scanner("a b  c\nd").tokens().collect(Collectors.toList()));
        System.out.println(new Scanner("a1b22c333").findAll("\\d+").map(r -> r.group() + "@" + r.start()).collect(Collectors.toList()));
        Scanner fa = new Scanner("x=1, y=22");
        System.out.println(fa.findAll("(\\w)=(\\d+)").map(r -> r.group(1) + r.group(2)).collect(Collectors.toList()) + " " + fa.hasNext());
        Scanner tk = new Scanner("p q r");
        tk.next();
        System.out.println(tk.tokens().collect(Collectors.toList()) + " " + tk.hasNext());

        Scanner t = new Scanner("hello 42 world");
        System.out.println(t);
        t.nextInt(36);
        System.out.println(t);
        t.hasNextInt();
        System.out.println(t);
        t.useDelimiter("o").useRadix(16);
        System.out.println(t);
        t.close();
        System.out.println(t);

        Scanner c = new Scanner("5 x");
        c.hasNextInt();
        c.useDelimiter("x");
        tryIt("cached after delimiter change", () -> c.nextInt());
        Scanner d = new Scanner("5 x");
        d.hasNextInt(16);
        tryIt("cached radix 16, ask 10", () -> d.nextInt());
        Scanner e = new Scanner("ff");
        e.hasNextInt(16);
        tryIt("cached ff radix 16 ask 10", () -> e.nextInt());
        tryIt("then next", () -> e.next());
    }
}

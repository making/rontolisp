// The grid k28 learned MessageFormat from: run under a JDK 25 and on the four backends
// (java Grid.java; rontolisp Grid.java, -o Grid.class, -o Grid.wasm, --component), every
// line is the JDK's but those of a format type, which the subset refuses by name, and of
// 1e23 and 2e23, whose digits a JDK takes from an older algorithm (.kb/java-frontend.md,
// "java.text.MessageFormat"). Written here over the public API.
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Grid {

    static class Odd extends Number {
        final double v;
        Odd(double v) { this.v = v; }
        public int intValue() { return (int) v; }
        public long longValue() { return (long) v; }
        public float floatValue() { return (float) v; }
        public double doubleValue() { return v; }
        public String toString() { return "Odd"; }
    }

    static void pattern(String p) {
        try {
            MessageFormat f = new MessageFormat(p, Locale.US);
            System.out.println("[" + p + "] -> [" + f.toPattern() + "] [" + f.format(new Object[] {"A", 1000, null}) + "]");
        } catch (RuntimeException e) {
            System.out.println("[" + p + "] " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static void arg(Object o) {
        System.out.println(new MessageFormat("<{0}>", Locale.US).format(new Object[] {o}));
    }

    public static void main(String[] args) {
        String[] patterns = {"", "x", "{0}", "{1}{0}", "'{0}'", "''", "it''s {0}", "'it''s' {0}", "'", "x'", "'{'",
            "'}'", "}", "x}y", "{", "{0", "{0}}", "{ 0 }", "{0 }", "{ 0}", "{00}", "{01}", "{-1}", "{+1}", "{a}", "{}",
            "{0,}", "{0, }", "{0,number}", "{0,date}", "{0,time}", "{0,choice,0#a|1#b}", "{0,list}", "{0,foo}",
            "{0,number,#}", "{0,number,integer}", "{2147483647}", "{2147483648}", "{9}", "{10}", "{0,{1}}",
            "'{0}' {0} ''{0}''", "a'b{0}c'd", "{0}'", "'''", "''''", "a''", "{0}{1}{2}{3}", "\\{0}", "{0}{0}",
            "{ 1 , number }", "x{0,number}y", "{0,NUMBER}", "{0, number }", "{0,string}", "{0,,}", "{0}{", "a{0b}",
            "{0x}", "{0\t}", "'a{'b", "{'0'}", "{0'}'}", "x{0}y'", "'x{0}y", "{1}'{2}'", "{0,number,'#'}",
            "{0,choice}", "{0,date,short}", "{0,a{b}c}", "{0,a'{'b}", "{0}}}", "}}{0}", "''''{0}''''", "'a''b'",
            "{99999999999}", "{ }", "{0,}x", "a}'b'{0}", "{ -0 }", "{-0}"};
        for (String p : patterns) {
            pattern(p);
        }
        Object[] values = {0, 1, -1, 12, 123, 999, 1000, -1000, 12345, 123456, 1234567, 123456789, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 1000L, (short) -12345, (byte) -128, 0.0, -0.0, 1.0, 1.5,
            0.0005, 0.0015, 0.0025, 1.0005, 1.0015, 2.0025, 0.1, 0.2, 0.3, 1234.5678, -1234.5678, -0.0001, -0.0005,
            -0.0006, 1e7, 1.0E23, 2e23, 9.007199254740993E15, 1e300, Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.125, 0.0625, -0.0625, 0.9995, 999.9995, 999999.9995,
            1e16, 1.2345678901234567E16, 1.0E-5, 0.1f, 1.5f, 1e10f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY,
            Float.MAX_VALUE, 'c', true, "text", null, new AtomicInteger(1234), new AtomicLong(-98765), new Odd(1234.5678),
            new Odd(Double.NaN), new StringBuilder("sb"), List.of(1000, 2), 1e-300, 4.35, 0.035,
            2.675, 1.0000005, 1.0004999999, 1.00050001, 0.00049999, 0.0004999999999999999, 1e-4, 9.9995, 99.9995};
        for (Object v : values) {
            arg(v);
        }
        Locale[] locales = {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA, null};
        for (Locale l : locales) {
            try {
                MessageFormat f = new MessageFormat("{0} {1} {2} {3} {4}", l);
                System.out.println(l + " " + f.getLocale() + " " + f.format(new Object[] {1234567, -1234.5678, Double.NaN, Double.NEGATIVE_INFINITY, "s"}));
            } catch (RuntimeException e) {
                System.out.println(l + " " + e.getClass().getName());
            }
        }
        System.out.println(MessageFormat.format("{0} and {1} and {2}", 1000, "b"));
        System.out.println(MessageFormat.format("{0}", (Object[]) null));
        System.out.println(MessageFormat.format("{0}", (Object) null));
        System.out.println(MessageFormat.format("{0}{1}", new Object[] {1, 2}));
        System.out.println(MessageFormat.format("none", new Object[0]));
        MessageFormat f = new MessageFormat("a {0}");
        System.out.println(f.getLocale() + " " + f.format(new Object[] {12345}) + " " + f.format(new Object[0]) + " " + f.format((Object[]) null));
        f.applyPattern("b ''{1}'' {0}");
        System.out.println(f.toPattern() + " " + f.format(new Object[] {1, 2}));
        try {
            f.applyPattern("{x}");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
        try {
            f.format("str");
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName());
        }
        try {
            new MessageFormat(null);
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName());
        }
        try {
            f.applyPattern(null);
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName());
        }
        // ties and magnitudes, seeded
        Random r = new Random(28);
        StringBuilder line = new StringBuilder();
        MessageFormat one = new MessageFormat("{0}", Locale.US);
        for (int i = 0; i < 4000; i++) {
            double x;
            switch (i % 4) {
                case 0 -> x = r.nextInt(2000000) / 2000.0;
                case 1 -> x = -r.nextInt(200000000) / 20000.0;
                case 2 -> x = r.nextDouble() * Math.pow(10, r.nextInt(30) - 6);
                default -> x = Double.longBitsToDouble(r.nextLong());
            }
            line.append(one.format(new Object[] {x})).append(' ');
            if (i % 8 == 7) {
                System.out.println(line);
                line.setLength(0);
            }
        }
    }
}

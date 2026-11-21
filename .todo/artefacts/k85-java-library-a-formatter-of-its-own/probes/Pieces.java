import java.util.*;
import java.io.*;

public class Pieces {
    static class Bad implements Appendable {
        int n; StringBuilder log = new StringBuilder();
        public Appendable append(CharSequence s) throws IOException { n++; log.append("cs" + n + " "); throw new IOException("bad" + n); }
        public Appendable append(CharSequence s, int a, int b) throws IOException { n++; log.append("r" + n + " "); throw new IOException("badr" + n); }
        public Appendable append(char c) throws IOException { n++; log.append("c" + n + " "); throw new IOException("badc" + n); }
    }
    static class F implements Formattable {
        public void formatTo(Formatter f, int flags, int width, int precision) {
            System.out.println("formatTo flags=" + flags + " width=" + width + " precision=" + precision + " locale=" + f.locale() + " out=" + f.out().getClass().getName() + "[" + f.out() + "]");
            f.format("<F%d>", flags);
        }
        public String toString() { return "Fstr"; }
    }
    static void t(String what, Runnable r) {
        try { r.run(); } catch (Exception e) { System.out.println(what + " -> " + e.getClass().getName() + ": " + e.getMessage()); }
    }
    public static void main(String[] args) throws Exception {
        Bad b = new Bad();
        Formatter f = new Formatter(b);
        f.format("a%3dz%-3sy", 1, "q");
        System.out.println(b.log + " io " + f.ioException());
        System.out.println(String.format("pre %s %S %-10s %10.2s %#s|", new F(), new F(), new F(), new F(), new F()));
        Formatter g = new Formatter(new StringBuilder("X"));
        g.format(Locale.UK, "pre %s", new F());
        System.out.println(g + " " + g.locale());
        t("hash", () -> System.out.println(String.format("%#s", "x")));
        t("hash2", () -> System.out.println(String.format("%#s", (Object) null)));
        System.out.println(String.format("%s|%b", new F(), new F()));
        System.out.println(FormattableFlags.LEFT_JUSTIFY + " " + FormattableFlags.UPPERCASE + " " + FormattableFlags.ALTERNATE);
        t("pre-wrap", () -> System.out.println(String.format("%s %d", new F(), "x")));
        System.out.printf("ps %s%n", new F().toString());
        Formatter n = new Formatter((Locale) null);
        n.format("%s", new F());
        System.out.println(n);
        System.out.println(String.format("%s %s", (Object[]) null));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Formatter o = new Formatter(bo);
        o.format("abc%d", 1);
        System.out.println("before flush " + bo.size() + " out " + o.out().getClass().getName());
        o.flush();
        System.out.println("after flush " + bo.size() + " " + bo);
        o.format("é");
        o.close();
        System.out.println("after close " + bo.size());
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        Formatter u = new Formatter(c, "UTF-16BE");
        u.format("A"); u.flush();
        System.out.println(Arrays.toString(c.toByteArray()) + " " + u.locale());
        try { new Formatter(c, "nope"); } catch (Exception e) { System.out.println(e); }
        try { new Formatter((OutputStream) null); } catch (Exception e) { System.out.println(e.getClass().getName()); }
        System.out.println(new Formatter(new ByteArrayOutputStream(), "UTF-8", Locale.UK).locale());
        try {
            throw new FormatFlagsConversionMismatchException("#", 'q');
        } catch (IllegalFormatException e) {
            System.out.println(e.getMessage() + " " + ((FormatFlagsConversionMismatchException) e).getFlags() + ((FormatFlagsConversionMismatchException) e).getConversion());
        }
        System.out.println(new IllegalFormatPrecisionException(3).getPrecision() + " " + new MissingFormatWidthException("%-d").getFormatSpecifier() + " " + new IllegalFormatFlagsException("+ ").getMessage() + " " + new IllegalFormatConversionException('d', String.class).getMessage());
        try { String.format("%d", "s"); } catch (IllegalFormatConversionException e) { System.out.println(e.getConversion() + " " + e.getMessage()); }
        PrintWriter pw = new PrintWriter(System.out, true);
        pw.printf("pw %tY %a%n", java.time.Year.of(2025), 1.0);
        PrintStream ps = System.out;
        ps.format(Locale.UK, "ps %G%n", 1e-5);
    }
}

import java.util.*;
import java.util.regex.*;

// Scanner over a String: tokens and lines, the numbers by radix, what is refused and how.
public class Scan1 {
    static String show(String s) {
        if (s == null) {
            return "null";
        }
        return "<" + s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\u2028", "\\u2028").replace("\u0085", "\\u0085").replace("\f", "\\f") + ">";
    }

    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            Object v = r.call();
            System.out.println(what + ": " + (v instanceof String s ? show(s) : String.valueOf(v)));
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + (e instanceof NullPointerException ? "" : show(e.getMessage())));
        }
    }

    static String tokens(Scanner sc) {
        StringBuilder b = new StringBuilder();
        while (sc.hasNext()) {
            b.append(show(sc.next()));
        }
        return b.toString();
    }

    static String lines(Scanner sc) {
        StringBuilder b = new StringBuilder();
        while (sc.hasNextLine()) {
            b.append(show(sc.nextLine()));
        }
        return b.toString();
    }

    public static void main(String[] args) {
        String[] inputs = {"", " ", "a", " a ", "a b\tc\nd\re\ff\u000bg\u001ch\u00a0i\u2003j", "\n\n", "x\n", "x\r\ny", "x\ry\rz", "a\u2028b\u0085c\u2029", "one  two\n\nthree   \n"};
        for (String in : inputs) {
            System.out.println(show(in) + " tokens " + tokens(new Scanner(in)) + " lines " + lines(new Scanner(in)));
        }
        String[][] delims = {{"a,b,,c,", ","}, {",a", ","}, {",,a", ","}, {"a,,", ","}, {"a , b ,c", "\\s*,\\s*"}, {"abc", ""}, {"a b", "\\s*"}, {"a1b22c", "\\d"}, {"xaay", "a*"}, {"1;2;3", ";"}, {";", ";"}, {"a\nb\n\nc", "\n"}, {"a\r\nb", "\\R"}};
        for (String[] d : delims) {
            System.out.println(show(d[0]) + " by " + show(d[1]) + " " + tokens(new Scanner(d[0]).useDelimiter(d[1])));
        }
        Scanner m = new Scanner("12 abc 3\n rest of line\nnext 4.5 true");
        System.out.println(m.nextInt() + " " + m.hasNextInt() + " " + m.next() + " " + m.nextInt() + show(m.nextLine()) + show(m.nextLine()) + " " + m.next() + " " + m.nextDouble() + " " + m.nextBoolean() + " " + m.hasNext() + " " + m.hasNextLine());
        String[] numbers = {"0", "-0", "+0", "12", "+12", "-12", "1,000", "1,000,000", "-1,000", "+1,000", "10,00", "1,0000", ",100", "1,", "01,000", "0,000", "2147483647", "2147483648", "-2147483648", "-2147483649", "9223372036854775807", "9223372036854775808", "1.5", "-1.5", ".5", "5.", "1e3", "1E-3", "1e", "-.5e+2", "1,000.25", "1,000e2", "NaN", "-NaN", "Infinity", "-Infinity", "+Infinity", "\u221e", "-\u221e", "nan", "infinity", "0x10", "0x1.8p1", "0x.8", "-0X1.0P-2", "0x1p3", "1.5f", "1.5d", "1_000", "\u0663\u0664", "\u0661,\u0662\u0663\u0664", "\uff11\uff12", "ff", "FF", "-ff", "7fffffff", "80000000", "z", "Z", "zz", "127", "128", "-129", "32767", "32768", "true", "TRUE", "False", "yes", "1.7976931348623157E308", "1e400", "4.9e-324", "1e-400", "123456789012345678901234567890", "0.1", "00012", "0012.50"};
        for (String n : numbers) {
            Scanner sc = new Scanner(n);
            StringBuilder b = new StringBuilder(show(n));
            b.append(" int:").append(sc.hasNextInt() ? sc.nextInt() : "-");
            sc = new Scanner(n);
            b.append(" int16:").append(sc.hasNextInt(16) ? sc.nextInt(16) : "-");
            sc = new Scanner(n);
            b.append(" int36:").append(sc.hasNextInt(36) ? sc.nextInt(36) : "-");
            sc = new Scanner(n);
            b.append(" long:").append(sc.hasNextLong() ? sc.nextLong() : "-");
            sc = new Scanner(n);
            b.append(" byte:").append(sc.hasNextByte() ? sc.nextByte() : "-");
            sc = new Scanner(n);
            b.append(" short:").append(sc.hasNextShort() ? sc.nextShort() : "-");
            sc = new Scanner(n);
            b.append(" double:").append(sc.hasNextDouble() ? sc.nextDouble() : "-");
            sc = new Scanner(n);
            b.append(" float:").append(sc.hasNextFloat() ? sc.nextFloat() : "-");
            sc = new Scanner(n);
            b.append(" big:").append(sc.hasNextBigInteger() ? sc.nextBigInteger() : "-");
            sc = new Scanner(n);
            b.append(" dec:").append(sc.hasNextBigDecimal() ? sc.nextBigDecimal() : "-");
            sc = new Scanner(n);
            b.append(" bool:").append(sc.hasNextBoolean() ? sc.nextBoolean() : "-");
            System.out.println(b);
        }
        for (String n : new String[] {"abc", "2147483648", "128", "1e400", "", "ff", "1.5"}) {
            tryIt("nextInt " + show(n), () -> new Scanner(n).nextInt());
            tryIt("nextByte " + show(n), () -> new Scanner(n).nextByte());
            tryIt("nextDouble " + show(n), () -> new Scanner(n).nextDouble());
            tryIt("nextBoolean " + show(n), () -> new Scanner(n).nextBoolean());
            tryIt("nextBigInteger " + show(n), () -> new Scanner(n).nextBigInteger());
        }
        Scanner keep = new Scanner("abc 5");
        tryIt("keep nextInt", () -> keep.nextInt());
        tryIt("keep next", () -> keep.next());
        tryIt("keep nextInt again", () -> keep.nextInt());
        tryIt("next at end", () -> new Scanner("").next());
        tryIt("next at end ws", () -> new Scanner("  ").next());
        tryIt("nextLine at end", () -> new Scanner("").nextLine());
        tryIt("nextInt at end", () -> new Scanner(" ").nextInt());
        tryIt("radix 1", () -> new Scanner("1").useRadix(1));
        tryIt("radix 37", () -> new Scanner("1").useRadix(37));
        tryIt("nextInt radix 37", () -> new Scanner("1").nextInt(37));
        tryIt("hasNextInt radix 1", () -> new Scanner("1").hasNextInt(1));
        Scanner closed = new Scanner("a b");
        closed.close();
        tryIt("closed next", () -> closed.next());
        tryIt("closed hasNext", () -> closed.hasNext());
        tryIt("closed nextLine", () -> closed.nextLine());
        tryIt("closed delimiter", () -> closed.delimiter());
        tryIt("closed radix", () -> closed.radix());
        tryIt("closed match", () -> closed.match());
        tryIt("closed close", () -> { closed.close(); return "again"; });
        tryIt("closed findInLine", () -> closed.findInLine("a"));
        tryIt("closed toString", () -> closed.toString().length() > 0);
        tryIt("remove", () -> { new Scanner("a").remove(); return null; });
        tryIt("no match", () -> new Scanner("a").match());
        tryIt("useDelimiter null", () -> new Scanner("a").useDelimiter((String) null));
        tryIt("hasNext null", () -> new Scanner("a").hasNext((String) null));
        tryIt("new null", () -> new Scanner((String) null));
        Scanner r = new Scanner("ff 10 z");
        r.useRadix(16);
        System.out.println(r.radix() + " " + r.nextInt() + " " + r.nextInt() + " " + r.hasNextInt() + " " + r.hasNextInt(36) + " " + r.nextInt(36));
        Scanner q = new Scanner("a b");
        System.out.println(q.delimiter() + " " + q.locale() + " " + q.radix() + " " + q.ioException());
        q.useDelimiter(",").useRadix(8).useLocale(Locale.ROOT);
        System.out.println(q.delimiter() + " " + q.locale() + " " + q.radix());
        q.reset();
        System.out.println(q.delimiter() + " " + q.locale() + " " + q.radix() + " " + q.next());
    }
}

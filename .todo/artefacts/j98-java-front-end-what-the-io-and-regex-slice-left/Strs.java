import java.util.*;

public class Strs {
    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String s = "hello, world; hello";
        for (int i : new int[] {-1, 0, 4, 18, 19, 100}) {
            final int at = i;
            t("codePointAt " + i, () -> s.codePointAt(at));
            t("codePointBefore " + i, () -> s.codePointBefore(at));
            t("lastIndexOf o " + i, () -> s.lastIndexOf('o', at));
            t("lastIndexOf hello " + i, () -> s.lastIndexOf("hello", at));
            t("lastIndexOf empty " + i, () -> s.lastIndexOf("", at));
            t("indexOf o " + i, () -> s.indexOf('o', at));
            for (int j : new int[] {-1, 0, 7, 19, 20}) {
                final int o = j;
                for (int n : new int[] {-1, 0, 3, 5, 6, 100}) {
                    final int len = n;
                    t("regionMatches " + i + " " + j + " " + n, () -> s.regionMatches(at, "HELLO, hello", o, len) + " " + s.regionMatches(true, at, "HELLO, hello", o, len) + " " + s.regionMatches(false, at, "HELLO, hello", o, len));
                }
            }
        }
        t("regionMatches null", () -> s.regionMatches(0, null, 0, 1));
        t("contentEquals", () -> s.contentEquals(new StringBuilder(s)) + " " + s.contentEquals(new StringBuffer("x")) + " " + s.contentEquals(s) + " " + s.contentEquals("hello") + " " + "".contentEquals(new StringBuilder()));
        t("contentEquals null", () -> s.contentEquals((CharSequence) null));
        char[] cs = {'a', 'b', 'c', 'd', 'e'};
        t("copyValueOf", () -> String.copyValueOf(cs) + " " + String.copyValueOf(cs, 1, 3) + " " + String.valueOf(cs, 2, 3) + " " + String.valueOf(cs, 5, 0));
        t("copyValueOf bad", () -> String.copyValueOf(cs, 3, 3));
        t("copyValueOf neg", () -> String.copyValueOf(cs, -1, 3));
        t("valueOf bad", () -> String.valueOf(cs, 1, -1));
        t("copyValueOf null", () -> String.copyValueOf(null));
        char[] dst = new char[8];
        Arrays.fill(dst, '.');
        s.getChars(7, 12, dst, 2);
        System.out.println(new String(dst));
        t("getChars bad begin", () -> { s.getChars(-1, 3, dst, 0); return "ok"; });
        t("getChars bad end", () -> { s.getChars(0, 30, dst, 0); return "ok"; });
        t("getChars begin>end", () -> { s.getChars(5, 3, dst, 0); return "ok"; });
        t("getChars dst small", () -> { s.getChars(0, 9, dst, 0); return "ok"; });
        t("getChars dst neg", () -> { s.getChars(0, 2, dst, -1); return "ok"; });
        t("getChars dst end", () -> { s.getChars(0, 2, dst, 7); return "ok"; });
        t("getChars empty", () -> { s.getChars(3, 3, dst, 8); return "ok"; });
        t("getChars empty past", () -> { s.getChars(3, 3, dst, 9); return "ok"; });
        for (String text : new String[] {"", "a", "a\nb", "a\nb\n", "  a\n    b\r\n\tc\n\n  \nd  ", "\n", "\n\n", "x\r", "x\ry", " \u2003 z"}) {
            for (int n : new int[] {0, 2, -1, -3, -100}) {
                System.out.println("indent " + n + " [" + text.indent(n).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "]");
            }
        }
        List<String> names = new ArrayList<>(List.of("bob", "Alice", "carol", "Bob", "alice", "ALICE", "dave", "Carol", "\u00e9", "\u00c9", "Z", "a"));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        System.out.println(names);
        Comparator<String> order = String.CASE_INSENSITIVE_ORDER;
        System.out.println(order.compare("abc", "ABD") + " " + order.compare("b", "A") + " " + order.reversed().compare("b", "A") + " " + order.compare("", "a") + " " + order.compare("stra\u00dfe", "STRASSE") + " " + order.compare("\u0130", "i") + " " + order.compare("\u0131", "I"));
        names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        System.out.println(names);
        t("isLatin", () -> "x".equalsIgnoreCase("X") + " " + "abc".endsWith("") + " " + "abc".startsWith("", 3) + " " + "abc".startsWith("", 4));
    }
}

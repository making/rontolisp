public class Pui2 {
    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        for (String text : new String[] {"abcdefghijkl", "x", "1x", "12x", "123x", "1234x", "12345x", "123456x", "1234567x", "12345678x", "123456789x", "1234567890x", "xxxxxxxxxx", "xxxxxxxxxxx", "+123456789x", "+12345678x", "zzzzzz", "zzzzzzz", "zzzzz", "1111111111111111111111111111111111", "+", "++1", "+-1", "1+", "000000000000000000000000000000001", "0000000000000000000000000000000x", "  ", "          ", "             "}) {
            t("pui " + text, () -> Integer.parseUnsignedInt(text));
            t("pui16 " + text, () -> Integer.parseUnsignedInt(text, 16));
            t("pui36 " + text, () -> Integer.parseUnsignedInt(text, 36));
            t("pui2 " + text, () -> Integer.parseUnsignedInt(text, 2));
        }
    }
}

public class Pui {
    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        for (String text : new String[] {"99999999999999999999x", "9999999999x", "99999999999999999999999999", "4294967296", "4294967295", "+4294967295", "42949672950", "x", "9223372036854775807", "9223372036854775808", "18446744073709551615", "18446744073709551616", "1844674407370955161x", "184467440737095516150", "+", "", "-5", "12345678901x"}) {
            t("pui " + text, () -> Integer.parseUnsignedInt(text));
            t("pui16 " + text, () -> Integer.parseUnsignedInt(text, 16));
            t("pul " + text, () -> Long.parseUnsignedLong(text));
        }
    }
}

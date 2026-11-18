public class Parse {
    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String[] texts = {"", "-", "+", "12", "-12", "zz", "1z", null, " 1", "99999999999", "-99999999999", "7fffffff", "80000000", "-80000000", "+7", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "4294967295", "4294967296", "-0", "+0", "--1", "1-"};
        int[] radixes = {10, 16, 2, 36, 37, 1, 0, -1, 8};
        for (String text : texts) {
            for (int radix : radixes) {
                t("parseInt " + text + " " + radix, () -> Integer.parseInt(text, radix));
                t("parseLong " + text + " " + radix, () -> Long.parseLong(text, radix));
                t("parseUnsignedInt " + text + " " + radix, () -> Integer.parseUnsignedInt(text, radix));
                t("parseUnsignedLong " + text + " " + radix, () -> Long.parseUnsignedLong(text, radix));
                t("parseByte " + text + " " + radix, () -> Byte.parseByte(text, radix));
                t("parseShort " + text + " " + radix, () -> Short.parseShort(text, radix));
            }
            t("parseInt " + text, () -> Integer.parseInt(text));
            t("valueOf " + text, () -> Integer.valueOf(text));
            t("parseLong " + text, () -> Long.parseLong(text));
            t("decode " + text, () -> Integer.decode(text));
            t("ldecode " + text, () -> Long.decode(text));
        }
        for (String text : new String[] {"-08", "-0x", "-#", "+#", "0X-", "-0xG", "+08", "0x 1", "-", "+", "0", "-00", "+0x7fffffff", "-0x80000001", "0x7fffffffffffffff", "-0x8000000000000001", "0xffffffffffffffffff", "#-1", "01777777777777777777777", "-01000000000000000000000", "١٢", "0x١", "99999999999999999999x", "x99999999999999999999"}) {
            t("decode " + text, () -> Integer.decode(text));
            t("ldecode " + text, () -> Long.decode(text));
            t("parseUnsignedLong " + text, () -> Long.parseUnsignedLong(text));
        }
        StringBuilder r = new StringBuilder();
        t("repeat both bad", () -> r.repeat(-1, -1));
        t("repeat bad count", () -> r.repeat(0x110000, -1));
    }
}

import java.math.*;

// What the Scanner hands its tokens to: the parses of the boxes and of java.math, and the
// characters the default delimiter and the digits are.
public class Prims {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        tryIt("parseInt arabic", () -> Integer.parseInt("٣٤"));
        tryIt("parseInt fullwidth", () -> Integer.parseInt("-１２"));
        tryIt("parseInt arabic 16", () -> Integer.parseInt("٣", 16));
        tryIt("parseInt overflow", () -> Integer.parseInt("2147483648"));
        tryIt("parseInt overflow 16", () -> Integer.parseInt("80000000", 16));
        tryIt("parseInt arabic overflow", () -> Integer.parseInt("٣٣٣٣٣٣٣٣٣٣"));
        tryIt("parseInt 9 radix 8", () -> Integer.parseInt("9", 8));
        tryIt("parseInt +12", () -> Integer.parseInt("+12"));
        tryIt("parseLong overflow", () -> Long.parseLong("9223372036854775808"));
        tryIt("parseLong arabic", () -> Long.parseLong("١٢"));
        tryIt("parseShort", () -> Short.parseShort("32768"));
        tryIt("parseShort 16", () -> Short.parseShort("8000", 16));
        tryIt("parseShort arabic", () -> Short.parseShort("١٢"));
        tryIt("parseByte", () -> Byte.parseByte("-129"));
        tryIt("parseByte 2", () -> Byte.parseByte("10000000", 2));
        tryIt("parseByte overflow int", () -> Byte.parseByte("2147483648"));
        tryIt("BigInteger 9 r3", () -> new BigInteger("9", 3));
        tryIt("BigInteger 13 r3", () -> new BigInteger("13", 3));
        tryIt("BigInteger 3000 r3", () -> new BigInteger("3000", 3));
        tryIt("BigInteger arabic", () -> new BigInteger("١٢"));
        tryIt("BigInteger arabic r3", () -> new BigInteger("٩", 3));
        tryIt("BigInteger +5", () -> new BigInteger("+5"));
        tryIt("BigInteger long r3", () -> new BigInteger("1239999999999999999999999999", 3));
        tryIt("BigInteger -FFFF r16", () -> new BigInteger("-FFFFFFFFFFFFFFFFFFFF", 16));
        for (String d : new String[] {"0x1.8p1", "-0X1.0P-2", "0x1.0p-1074", "0X1.FFFFFFFFFFFFFP1023", "0x.8", "0x1.8", "-NaN", "+NaN", "+Infinity", "-Infinity", "∞", "-∞", "1e400", "4.9e-324", "-.5e+2", "5.", ".5", "1E-3", "0012.50", "1000.0005"}) {
            tryIt("double " + d, () -> Double.parseDouble(d));
            tryIt("float " + d, () -> Float.parseFloat(d));
        }
        for (String d : new String[] {"5.", "-.5e+2", "1000e2", "1e+5", "1E-0", "-00.0", "1e2147483648", "1e99999999999", "1.5e3", "0012.50", "1e400"}) {
            tryIt("BigDecimal " + d, () -> new BigDecimal(d));
        }
        StringBuilder ws = new StringBuilder();
        for (int c = 0; c <= 0x10FFFF; c++) {
            if (Character.isWhitespace(c)) {
                ws.append(Integer.toHexString(c)).append(' ');
            }
        }
        System.out.println("whitespace " + ws);
        int n = 0;
        long sum = 0;
        for (int c = 128; c <= 0x10FFFF; c++) {
            if (Character.isDigit(c)) {
                n++;
                sum += c;
            }
        }
        System.out.println("non-ascii digits " + n + " " + sum);
        System.out.println(new IllegalArgumentException(new java.nio.charset.UnsupportedCharsetException("no-such")).getMessage());
    }
}

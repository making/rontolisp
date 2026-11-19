import java.math.*;
import java.util.*;

public class Fmt {
    static void f(String control, Object... args) {
        String out;
        try {
            out = "[" + String.format(control, args) + "]";
        }
        catch (RuntimeException e) {
            out = e.toString();
        }
        System.out.println(control + " " + Arrays.toString(args) + " -> " + out);
    }

    public static void main(String[] args) {
        BigInteger[] ints = { BigInteger.ZERO, BigInteger.ONE, new BigInteger("-1"), new BigInteger("255"), new BigInteger("-255"), new BigInteger("123456789012345678901234567890"), new BigInteger("-123456789012345678901234567890"), BigInteger.TWO.pow(64), BigInteger.TWO.pow(64).negate(), new BigInteger("1000") };
        String[] whole = { "%d", "%,d", "%+d", "% d", "%(d", "%10d", "%-10d|", "%010d", "%+,15d", "%(,d", "%x", "%X", "%#x", "%#X", "%o", "%#o", "%08x", "%+x", "%(x", "% o", "%-8x|", "%,x", "%s", "%S", "%10s", "%f", "%e", "%c", "%b", "%.2d", "%+010d", "%(010d", "%#010x", "%+#x", "%0(12o" };
        for (String c : whole) {
            for (BigInteger n : ints) {
                f(c, n);
            }
        }
        BigDecimal[] decs = { BigDecimal.ZERO, new BigDecimal("0.00"), BigDecimal.ONE, new BigDecimal("-1"), new BigDecimal("1.5"), new BigDecimal("-2.5"), new BigDecimal("0.125"), new BigDecimal("1234567.891"), new BigDecimal("-0.000123456"), new BigDecimal("123456789012345678901234567890.123456789"), new BigDecimal("1E+3"), new BigDecimal("1.23E-10"), new BigDecimal("9.995"), new BigDecimal("0.5"), new BigDecimal("99999.5"), new BigDecimal("-0.0"), new BigDecimal("1E+30"), new BigDecimal("0.15"), new BigDecimal("2.675"), new BigDecimal("1.005") };
        String[] parts = { "%f", "%.0f", "%.1f", "%.2f", "%.10f", "%,f", "%,.2f", "%+f", "% .2f", "%(.2f", "%12.3f", "%-12.3f|", "%012.3f", "%#.0f", "%e", "%E", "%.0e", "%.2e", "%.10e", "%+e", "%(e", "%15.3e", "%-15.3e|", "%015.3e", "%#.0e", "%s", "%d", "%x", "%10s", "%,e", "%+,.1f", "%(,010.1f" };
        for (String c : parts) {
            for (BigDecimal n : decs) {
                f(c, n);
            }
        }
        f("%d and %f and %s", new BigInteger("42"), new BigDecimal("4.2"), new BigInteger("7"));
        f("%2$d %1$s", new BigDecimal("4.2"), new BigInteger("7"));
        f("%d %d %d", 1, 2L, new BigInteger("3"));
        f("%f %f", 1.5, new BigDecimal("1.5"));
        f("%d", (Object) null);
        f("%d", new Object());
        f("%d", "text");
        f("%f", new BigInteger("3"));
        f("%d", new BigDecimal("3"));
        f("%x", new BigDecimal("3"));
        f("%e", new BigInteger("3"));
        f("%c", new BigInteger("65"));
        f("%d", new java.util.concurrent.atomic.AtomicInteger(3));
        f("%f", new java.util.concurrent.atomic.AtomicLong(3));
        System.out.println(new BigInteger("1234567").toString().formatted() + " " + "%,d".formatted(new BigInteger("1234567")) + " " + String.format(Locale.ROOT, "%,.3f", new BigDecimal("1234567.8915")));
        System.out.printf("%d %.3e%n", new BigInteger("99"), new BigDecimal("12345.6789"));
    }
}

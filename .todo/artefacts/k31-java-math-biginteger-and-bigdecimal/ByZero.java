import java.math.*;
import java.util.function.Supplier;

// The two wordings of a division by zero at a scale: a JDK 25's lines, and the four
// backends'. Written here.
public class ByZero {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
    static BigDecimal d(String s) { return new BigDecimal(s); }
    public static void main(String[] a) {
        String[] dividends = {"1", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "1E+30", "1E-30", "0", "0E+30", "0E-30", "92233720368547758.07", "92233720368547758.08", "100000000000000000000", "0.0000000000000000000000001"};
        String[] zeros = {"0", "0.00", "0E+30", "0E-30"};
        int[] scales = {0, 30, -30, 5};
        for (String x : dividends) for (String z : zeros) for (int s : scales)
            t(x + " / " + z + " @" + s, () -> d(x).divide(d(z), s, RoundingMode.HALF_UP));
    }
}

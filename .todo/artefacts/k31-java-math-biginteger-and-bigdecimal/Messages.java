import java.math.*;
import java.text.MessageFormat;

// What a message format writes for a BigDecimal and a BigInteger: a JDK 25's lines, and
// the four backends'. Written here.
public class Messages {
    public static void main(String[] a) {
        String[] texts = {"0", "-0", "0.000", "1", "-1", "1234", "1234567", "-1234567.891", "0.5", "0.0005", "0.0015", "0.0025", "-0.0005", "-0.0004", "-0.0006", "1.2345", "1.2355", "1.23450000001", "999.9995", "999999.9995", "-999.9995", "1E+3", "1.5E+10", "1E-10", "-1E-10", "12345678901234567890123.45678", "0.1", "0.10", "0.100", "0.1000", "100.00", "2.5", "1.0005", "1.00050", "-0.0015", "123456789012345678901234567890"};
        for (String s : texts) System.out.println(s + " -> " + MessageFormat.format("{0}", new BigDecimal(s)));
        String[] ints = {"0", "-1", "999", "1000", "-1000", "123456789012345678901234567890", "-123456789012345678901234567890"};
        for (String s : ints) System.out.println(s + " -> " + MessageFormat.format("[{0}]", new BigInteger(s)));
    }
}

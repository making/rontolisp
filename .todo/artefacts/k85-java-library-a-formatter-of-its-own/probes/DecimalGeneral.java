import java.math.*;
public class DecimalGeneral {
    public static void main(String[] args) {
        for (String s : new String[]{ "0", "0.0", "0.000", "0.0000", "0.00000", "0E+3", "0E-10", "0E+1", "0.0001", "0.00009999", "0.000099995", "0.00010", "123456", "999999.5", "1E+6", "-0.0001"})
            for (String f : new String[]{ "%g", "%.1g", "%.3g", "%.10g", "%e", "%.0e" })
                System.out.println(s + " " + f + " " + String.format(f, new BigDecimal(s)));
    }
}

import java.math.BigInteger;

public class FoBig {
    public static void main(String[] args) {
        System.out.println(String.format("%5d|%-6s|%.2f|%x", 42, "ab", 3.14159, 255) + new BigInteger("12345678901234567890").add(BigInteger.ONE));
    }
}

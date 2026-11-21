import java.math.BigInteger;

public class SzBigFormat {
    public static void main(String[] args) {
        System.out.println(String.format("%d|%s", new BigInteger("12345678901234567890"), "ab"));
    }
}

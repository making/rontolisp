import java.util.*;

// Scanner: which characters a number of each radix takes, as the refusals' words tell.
public class Scan7 {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String[] tokens = {"9", "19", "3,000", "1,239", "٩", "٠,٠٠٠", "١,٩٩٩", "a,bcd", "0,abc", "g", "-z", "+Z,ZZZ", "8", "2", "９", "1,٣٣٣", "٣,333"};
        int[] radixes = {2, 3, 8, 10, 16, 36};
        for (String t : tokens) {
            for (int r : radixes) {
                tryIt(t + " r" + r + " has " + new Scanner(t).hasNextInt(r), () -> new Scanner(t).nextInt(r));
            }
            tryIt(t + " long r3", () -> new Scanner(t).nextLong(3));
            tryIt(t + " big r3", () -> new Scanner(t).nextBigInteger(3));
            tryIt(t + " short r3", () -> new Scanner(t).nextShort(3));
            tryIt(t + " byte r3", () -> new Scanner(t).nextByte(3));
            tryIt(t + " double", () -> new Scanner(t).nextDouble());
            tryIt(t + " dec", () -> new Scanner(t).nextBigDecimal());
        }
        tryIt("exp 1e٣", () -> new Scanner("1e٣").nextDouble());
        tryIt("1٫5", () -> new Scanner("1٫5").hasNextDouble());
        tryIt("big 0x", () -> new Scanner("1e5").nextBigInteger(16));
        tryIt("double big", () -> new Scanner("123,456,789,012,345,678,901").nextDouble());
        tryIt("float of 1e39", () -> new Scanner("1e39").nextFloat());
        tryIt("float of 0x1p-150", () -> new Scanner("0x1.0p-150").nextFloat());
        tryIt("dec of 1e+5", () -> new Scanner("1e+5").nextBigDecimal());
        tryIt("dec of 1E-0", () -> new Scanner("1E-0").nextBigDecimal());
        tryIt("dec of -00.0", () -> new Scanner("-00.0").nextBigDecimal());
        tryIt("dec of 1e99999999999", () -> new Scanner("1e99999999999").hasNextBigDecimal());
        tryIt("dec of 1e2147483648", () -> new Scanner("1e2147483648").nextBigDecimal());
        StringBuilder ws = new StringBuilder();
        for (int c = 0; c <= 0x10FFFF; c++) {
            if (Character.isWhitespace(c)) {
                ws.append(Integer.toHexString(c)).append(' ');
            }
        }
        System.out.println("whitespace " + ws);
        StringBuilder digits = new StringBuilder();
        int n = 0;
        long sum = 0;
        for (int c = 128; c <= 0x10FFFF; c++) {
            if (Character.isDigit(c)) {
                n++;
                sum += c;
            }
        }
        System.out.println("non-ascii digits " + n + " " + sum);
        System.out.println(Integer.parseInt("٣٤") + " " + Long.parseLong("-１２") + " " + Integer.parseInt("٣", 16));
    }
}

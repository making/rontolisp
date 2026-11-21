import java.time.Duration;

public class Div {
    static void by(Duration d, long n) {
        try {
            System.out.println(d + " / " + n + " = " + d.dividedBy(n));
        } catch (ArithmeticException e) {
            System.out.println(d + " / " + n + " ! " + e.getMessage());
        }
    }

    static void by(Duration d, Duration e) {
        try {
            System.out.println(d + " / " + e + " = " + d.dividedBy(e));
        } catch (ArithmeticException x) {
            System.out.println(d + " / " + e + " ! " + x.getMessage());
        } catch (NullPointerException x) {
            System.out.println(d + " / " + e + " ! NPE " + x.getMessage());
        }
    }

    public static void main(String[] args) {
        Duration[] ds = {Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(-10), Duration.ofNanos(1), Duration.ofNanos(-1), Duration.ofSeconds(7, 500_000_000), Duration.ofSeconds(-7, -500_000_000), Duration.ofSeconds(Long.MAX_VALUE, 999_999_999), Duration.ofSeconds(Long.MIN_VALUE), Duration.ofMillis(1500), Duration.ofDays(3)};
        long[] ns = {1, -1, 2, -2, 3, -3, 7, 1000, 1_000_000_000L, 3_000_000_007L, Long.MAX_VALUE, Long.MIN_VALUE, 0};
        for (Duration d : ds) {
            for (long n : ns) {
                by(d, n);
            }
        }
        Duration[] es = {Duration.ofSeconds(3), Duration.ofSeconds(-3), Duration.ofNanos(1), Duration.ofNanos(-1), Duration.ofMillis(250), Duration.ofSeconds(Long.MAX_VALUE, 999_999_999), Duration.ofSeconds(Long.MIN_VALUE), Duration.ZERO, Duration.ofNanos(7), null};
        for (Duration d : ds) {
            for (Duration e : es) {
                by(d, e);
            }
        }
    }
}

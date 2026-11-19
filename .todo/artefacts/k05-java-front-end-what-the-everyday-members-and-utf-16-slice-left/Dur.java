import java.time.DateTimeException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

public class Dur {

    interface Made {
        Object run();
    }

    static void show(String what, Made made) {
        try {
            System.out.println(what + ": " + made.run());
        } catch (DateTimeParseException e) {
            System.out.println(what + ": " + e.getClass().getName() + ": " + e.getMessage() + " [" + e.getParsedString()
                    + "] " + e.getErrorIndex() + " cause " + e.getCause());
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static String all(Duration d) {
        StringBuilder b = new StringBuilder();
        b.append(d).append(" s=").append(d.getSeconds()).append(" n=").append(d.getNano());
        b.append(" zero=").append(d.isZero()).append(" neg=").append(d.isNegative()).append(" pos=").append(d.isPositive());
        b.append(" days=").append(d.toDays()).append(" hours=").append(d.toHours()).append(" minutes=").append(d.toMinutes());
        b.append(" seconds=").append(d.toSeconds());
        b.append(" parts=").append(d.toDaysPart()).append('/').append(d.toHoursPart()).append('/').append(d.toMinutesPart())
                .append('/').append(d.toSecondsPart()).append('/').append(d.toMillisPart()).append('/').append(d.toNanosPart());
        b.append(" hash=").append(d.hashCode());
        return b.toString();
    }

    public static void main(String[] args) {
        long[] seconds = {0, 1, -1, 59, 60, 61, -61, 3599, 3600, 3661, -3661, 86399, 86400, 90061, -90061, 4294967296L,
                -4294967296L, 31556952000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1};
        long[] nanos = {0, 1, -1, 500000000, -500000000, 999999999, -999999999, 1000000000, 1500000000, 123456789,
                120000000, 1000, 1000000, -1000000};
        for (long s : seconds) {
            for (long n : nanos) {
                show("ofSeconds(" + s + "," + n + ")", () -> all(Duration.ofSeconds(s, n)));
            }
        }
        long[] counts = {0, 1, -1, 999, 1000, 1001, -999, -1000, -1001, 1500, -1500, 86400000, Long.MAX_VALUE,
                Long.MIN_VALUE, 106751991167300L, 106751991167301L, 153722867280912930L, 153722867280912931L,
                2562047788015215L, 2562047788015216L, -2562047788015216L, 9223372036854775L, 9223372036854776L};
        for (long c : counts) {
            show("ofDays(" + c + ")", () -> Duration.ofDays(c));
            show("ofHours(" + c + ")", () -> Duration.ofHours(c));
            show("ofMinutes(" + c + ")", () -> Duration.ofMinutes(c));
            show("ofMillis(" + c + ")", () -> all(Duration.ofMillis(c)));
            show("ofNanos(" + c + ")", () -> all(Duration.ofNanos(c)));
        }
        Duration[] values = {Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofMillis(1500),
                Duration.ofMillis(-1500), Duration.ofNanos(1), Duration.ofNanos(-1), Duration.ofSeconds(3661, 5),
                Duration.ofSeconds(-3661, -5), Duration.ofDays(2), Duration.ofSeconds(Long.MAX_VALUE, 999999999),
                Duration.ofSeconds(Long.MIN_VALUE), Duration.ofSeconds(9223372036854775L, 807000000),
                Duration.ofSeconds(-9223372036854776L, 192000000), Duration.ofSeconds(9223372036L, 854775807),
                Duration.ofSeconds(-9223372037L, 145224192), Duration.ofSeconds(-9223372037L, 145224191)};
        for (Duration d : values) {
            show(d + ".toMillis", d::toMillis);
            show(d + ".toNanos", d::toNanos);
            show(d + ".negated", d::negated);
            show(d + ".abs", d::abs);
            show(d + ".withSeconds(7)", () -> d.withSeconds(7));
            show(d + ".withNanos(7)", () -> d.withNanos(7));
            for (long m : new long[] {0, 1, -1, 2, -2, 3, 1000, 1000000007L, -1000000007L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                show(d + ".multipliedBy(" + m + ")", () -> d.multipliedBy(m));
            }
            for (long c : new long[] {0, 1, -1, 1500, -1500, 86400001, Long.MAX_VALUE, Long.MIN_VALUE}) {
                show(d + ".plusDays(" + c + ")", () -> d.plusDays(c));
                show(d + ".plusHours(" + c + ")", () -> d.plusHours(c));
                show(d + ".plusMinutes(" + c + ")", () -> d.plusMinutes(c));
                show(d + ".plusSeconds(" + c + ")", () -> d.plusSeconds(c));
                show(d + ".plusMillis(" + c + ")", () -> d.plusMillis(c));
                show(d + ".plusNanos(" + c + ")", () -> d.plusNanos(c));
                show(d + ".minusDays(" + c + ")", () -> d.minusDays(c));
                show(d + ".minusHours(" + c + ")", () -> d.minusHours(c));
                show(d + ".minusMinutes(" + c + ")", () -> d.minusMinutes(c));
                show(d + ".minusSeconds(" + c + ")", () -> d.minusSeconds(c));
                show(d + ".minusMillis(" + c + ")", () -> d.minusMillis(c));
                show(d + ".minusNanos(" + c + ")", () -> d.minusNanos(c));
            }
            for (Duration e : values) {
                show(d + ".plus(" + e + ")", () -> d.plus(e));
                show(d + ".minus(" + e + ")", () -> d.minus(e));
                show(d + ".compareTo(" + e + ")", () -> Integer.signum(d.compareTo(e)) + " " + d.equals(e));
            }
        }
        show("withNanos(-1)", () -> Duration.ZERO.withNanos(-1));
        show("withNanos(1000000000)", () -> Duration.ZERO.withNanos(1000000000));
        show("equals", () -> Duration.ofSeconds(1).equals("PT1S") + " " + Duration.ofMillis(1000).equals(Duration.ofSeconds(1)));
        Map<Duration, String> map = new HashMap<>();
        map.put(Duration.ofMinutes(1), "a");
        show("map", () -> map.get(Duration.ofSeconds(60)));
        String[] texts = {"PT0S", "PT1S", "pt1s", "P1D", "p1d", "P1DT1H", "P1DT", "PT", "P", "", "T1S", "PT1H", "PT1M", "PT1H1M",
                "PT1H1M1S", "PT1H1S", "PT1M1H", "PT1S1M", "P1DT1H1M1.5S", "PT1.5S", "PT1,5S", "PT1.S", "PT.5S", "PT-1.5S",
                "PT-0.5S", "PT+0.5S", "-PT0.5S", "+PT0.5S", "-PT-0.5S", "PT1.123456789S", "PT1.1234567890S", "PT0.000000001S",
                "PT-0.000000001S", "P-1D", "P+1D", "P1D1H", "PT1D", "P1H", "P1S", "P1M", "P1Y", "P1W", " PT1S", "PT1S ",
                "PT 1S", "PT1 S", "PT--1S", "PT+-1S", "PT1.5M", "PT1.5H", "P1.5D", "P1DT1.5S", "PT9223372036854775807S",
                "PT9223372036854775808S", "PT-9223372036854775808S", "PT-9223372036854775809S", "P106751991167300D",
                "P106751991167301D", "PT2562047788015215H", "PT2562047788015216H", "PT153722867280912930M",
                "PT153722867280912931M", "P106751991167300DT24H", "-PT-9223372036854775808S", "PT9223372036854775807.999999999S",
                "-PT9223372036854775807.999999999S", "PT-9223372036854775808.5S", "PT1H-1M", "PT-1H+1M", "P2DT-48H",
                "PT00001S", "PT1.50S", "PT1.S1", "PT1S1", "PTS", "PT1", "P1DT1", "１", "PT１S", "PT1.１S", "PT0.5S0.5S",
                "PT1M1M", "P1D1D", "PT1e3S", "PT0x1S", "PT1.5.5S", "PT1..5S", "PT1.,5S", "PT+S", "PT-S", "P-D", "PT1.-5S",
                "PT1.+5S", "PT1.5 S", "PT92233720368547758070S", "PT-92233720368547758080S", "PT99999999999999999999999S", "P99999999999999999999D", "PT-99999999999999999999H", "PT+9223372036854775808S", "PT9223372036854775807M"};
        for (String text : texts) {
            show("parse(" + text + ")", () -> all(Duration.parse(text)));
        }
        show("parse(null)", () -> Duration.parse(null));
        show("parse(sb)", () -> Duration.parse(new StringBuilder("PT8H6M12.345S")));
        show("interrupted sleep", () -> {
            Thread.currentThread().interrupt();
            try {
                Thread.sleep(Duration.ofMillis(-1));
                System.out.println("negative returns " + Thread.currentThread().isInterrupted());
                Thread.sleep(Duration.ZERO);
                return "zero returns";
            } catch (InterruptedException e) {
                return e + " " + Thread.currentThread().isInterrupted();
            }
        });
        show("sleep", () -> {
            try {
                long before = System.nanoTime();
                Thread.sleep(Duration.ofMillis(20));
                return System.nanoTime() - before >= 20000000L;
            } catch (InterruptedException e) {
                return e;
            }
        });
    }
}

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Random;
import java.util.function.Supplier;

// The measurement's probe of java.util.Date and java.sql's Date, Time and Timestamp,
// written here: what each member answers over a grid of instants, texts and fields.
// Run under a JDK and on the four backends with TZ=UTC; the lines are compared whole.
public class Grid {

    static void p(String line) {
        System.out.println(line);
    }

    static String shown(RuntimeException e) {
        // a JDK's words for a null are its own (helpful NullPointerExceptions)
        if (e instanceof NullPointerException) {
            return "java.lang.NullPointerException";
        }
        return e.toString() + (e.getCause() != null ? " / " + e.getCause() : "");
    }

    static void t(String what, Supplier<Object> s) {
        try {
            Object v = s.get();
            p(what + " -> " + v + (v instanceof java.util.Date d ? " @" + d.getTime() : ""));
        }
        catch (RuntimeException e) {
            p(what + " ! " + shown(e));
        }
    }

    static void instant(long m) {
        java.util.Date d = new java.util.Date(m);
        t("ms " + m, () -> d + " | " + d.hashCode() + " | " + new java.sql.Date(m) + " | " + new Time(m) + " | "
                + new Timestamp(m) + " | " + new Timestamp(m).getNanos() + " " + new Timestamp(m).getTime() + " "
                + new Timestamp(m).hashCode());
        t("  instant", () -> d.toInstant() + " " + new Timestamp(m).toInstant());
        t("  local", () -> new java.sql.Date(m).toLocalDate() + " " + new Time(m).toLocalTime() + " "
                + new Timestamp(m).toLocalDateTime());
    }

    public static void main(String[] args) {
        long[] ms = { 0L, -1L, 1L, 999L, -999L, -1000L, 1700000000123L, -12219292800000L, -12219292800001L,
                -12219292800000L - 86400000L * 10, -12219292800000L - 86400000L * 11, -62135596800000L,
                -62135596800001L, -62135596800000L - 86400000L * 366, -62135596800000L - 86400000L * 367,
                -62135596800000L - 86400000L * 365 * 2, 253402300799999L, 253402300800000L, -62198755200000L,
                -100000000000000L, 100000000000000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE / 2,
                Long.MIN_VALUE / 2, -2208988800000L, -30610224000000L, -30610224000000L - 86400000L,
                -30610224000000L - 86400000L * 30, 951782400000L, 951868800000L, -2203891200000L, 4107542400000L,
                -14825894400000L, -59006534400000L, -62036064000000L, 1709251199999L, 1709251200000L };
        for (long m : ms) {
            instant(m);
        }
        Random random = new Random(37);
        for (int i = 0; i < 300; i++) {
            instant(random.nextLong() % 400000000000000L);
        }
        for (int i = 0; i < 40; i++) {
            instant(random.nextLong());
        }
        String[] dates = { "2024-01-15", "2024-1-5", "2024-02-30", "2024-02-31", "2024-02-32", "2024-13-01",
                "2024-00-01", "2024-01-00", "0000-01-01", "0001-01-01", "999-01-01", "99-01-01", "12345-01-01",
                "2024-001-01", "2024-01-001", "2024/01/15", "2024-01-15 ", " 2024-01-15", "2024-01", "2024-01-15-",
                "-2024-01-15", "+2024-01-15", "2024--01-15", "2024-01--15", "abcd-01-15", "2024-0a-15", "1582-10-10",
                "1582-10-04", "1582-10-15", "1582-10-05", "2024-1-", "2024--1", "", "-", "--", "2024-01-1x",
                "２０２４-01-15", "2024-1-1", "2024-12-31", "1900-02-29", "1000-02-29", "2023-02-29",
                "1582-09-31", "1582-10-31", "1582-02-29", "1500-02-29", "1600-02-30", "1582-10-14", "0004-02-29",
                "0100-02-29", "0001-02-29", "0000-02-29", "1582-12-31", "2024-+1-15", "2024-1-+5", "+202-01-15",
                "2024- 1-15", "-202-01-15", "2024-01-15\n", "2024-01-1١", "2024-13-0a", "abcd-13-01",
                "2024-1a-32", "2024-00-1a", "2024-01-+", "202-01-15", "20245-01-15", "2024-123-1", "2024-1-123",
                "2024-01-", "-2024-1", "2024-1-1-", "2024-1-1-1" };
        for (String s : dates) {
            t("D " + s, () -> java.sql.Date.valueOf(s));
            t("  " + s, () -> java.sql.Date.valueOf(s).toLocalDate());
        }
        t("D null", () -> java.sql.Date.valueOf((String) null));
        String[] times = { "10:15:30", "1:2:3", "00:00:00", "23:59:59", "24:00:00", "25:61:61", "99:99:99",
                "100:00:00", "10:15", "10:15:30.5", "10:15:30:00", "10-15-30", " 10:15:30", "10:15:30 ", "-1:15:30",
                "+1:15:30", "10::30", "::", "", "a:b:c", "10:15:3x", "1:1:1", "010:15:30", "10:015:30", "10:15:",
                ":15:30", "10:15:30.", "10:", "10", ":", "1:2:3:4", " 1:2:3", "1: 2:3", "1:2: 3", "2147483648:0:0",
                "0:0:-1", "1:2:٣", "-0:0:0", "596523:14:07", "-596523:-14:-8" };
        for (String s : times) {
            t("T " + s, () -> Time.valueOf(s));
            t("  " + s, () -> Time.valueOf(s).toLocalTime());
        }
        t("T null", () -> Time.valueOf((String) null));
        String[] stamps = { "2024-01-15 10:15:30", "2024-01-15 10:15:30.0", "2024-01-15 10:15:30.123",
                "2024-01-15 10:15:30.123456789", "2024-01-15 10:15:30.1234567891", "2024-01-15 10:15:30.",
                "2024-1-5 1:2:3", "2024-01-15 10:15", "2024-01-15", "2024-01-15T10:15:30", "2024-01-15  10:15:30",
                " 2024-01-15 10:15:30", "2024-01-15 10:15:30 ", "2024-02-30 10:15:30", "2024-13-01 10:15:30",
                "2024-00-01 10:15:30", "2024-01-00 10:15:30", "2024-01-32 10:15:30", "2024-01-15 24:00:00",
                "2024-01-15 25:61:61", "2024-01-15 10:60:00", "2024-01-15 10:15:60", "999-01-15 10:15:30",
                "12345-01-15 10:15:30", "2024-01-15 10:15:30.-1", "2024-01-15 10:15:30.+1", "2024-01-15 10:15:30.1a",
                "2024-01-15 10:15:30.000000001", "2024-01-15 10:15:30.100", "2024-01-15 100:15:30",
                "2024-001-15 10:15:30", "1582-10-10 00:00:00", "0000-01-01 00:00:00", "-1-01-01 00:00:00",
                "2024-01-15 10:15:30.12345678912", "2024-01-15 1:1:1.1", "x", "", "2024-01-15 10:15:3x",
                "2024-01-15 10:15:-3", "2024-01-15 -1:15:30", "1582-10-04 24:00:00", "1582-10-14 24:00:00",
                "1582-10-15 -1:00:00", "1582-10-04 23:59:60", "1582-09-30 48:00:00", "1582-10-04 1000:00:00",
                "1582-10-31 -1000:00:00", "2024-01-15 10:15:30.0001", "2024-01-15 10:15:30.00000000",
                "2024-01-15 10:15:30.1 ", "2024-01-15 10:15:30.1\t", "\t2024-01-15 10:15:30", "2024-01-15\t10:15:30",
                "2024-01-15 10:15:30.a", "2024-01-15 10:15:30. 1", "2024-01-15 10:15:30.1 2", "2024-01-15 10:15:30..1",
                "2024-01-15 10:15:30.١", "2024-01-15 10:15:+30", "2024-01-15 +10:15:30", "2024-01-15 10:+15:30",
                "2024-01-15 10:15:30:1", "2024-01-15 10:15:30.1:1", "2024-01-15 :15:30", "2024-01-15 10::30",
                "2024-01-15 10:15:", "2024-01-15 10.15:30", "2024-0a-15 10:15:30", "abcd-01-15 10:15:30",
                "2024-01-1a 10:15:30", "2024-1-15 10:15:30", "2024-01-5 10:15:30", "2024-01-15 x",
                "2024-01-15 10:15:30.1234567890", "202a-01-15 10:15:30", "2024-01-15-1 10:15:30",
                "2024--1-15 10:15:30", "2024 10:15:30", "2024-01-15 10:15:30.99999999999",
                "2024-01-15 2147483648:00:00", "2024-01-15 10:15:30 x", "2024-01-15 1 0:15:30",
                "2024-01-15 10:15:30.000000000", "abcd-01-15 1x:00:00", "abcd-01-15 x", "2024-13-01 1x:00:00",
                "2024-13-01 10:15", "2024-1x-01 10:15", "2024-13-01 10:15:30.a", "2024-1x-01 10:15:30.a",
                "2024-1x-01 10:15:30.1a", "2024-01-1x 1x:15:30.1a", "2024-01-15 1x:1y:30.1a", "2024-01-15 10:1y:3z",
                "2024-01-15 10:15:3z.1a", "2024-01-32 1x:00:00", "2024-01- 10:15:30", "-2024-01-15 10:15:30",
                "2024-01-15 .10:15:30", "2024-01-15 10:15.5:30", "2024-01-15 10:15:30.123456789x",
                "2024-01-15 10:15:30.12345678x", "2024-01-15 :", "2024-01-15 ::", "2024-01-15 ::1", "2024-01-15 1::",
                "2024-01-15  ", "2024-01-15 ", " ", "2024-01-15 1x:00:00.", "2024-01-15 1x:00:00.a",
                "2024-01-15 1x:00:00.1234567890", "2024-01-15 1x:00:", "2024-01-15 1x:00", "2024-13-01 1x:00",
                "2024-1x-01 1x:00", "2024-01-15 10:00:0x.", "2024-01-15 10:00:0x.a", "2024-01-15 10:00:0x.1234567890",
                "2024-01-15 10:00:00.a1", "2024-01-15 10:00:00.1x", "2024-01-15 10:00:00.١٢",
                "2024-01-15 10:00:00.１" };
        for (String s : stamps) {
            t("S " + s, () -> Timestamp.valueOf(s));
            t("  " + s, () -> Timestamp.valueOf(s).toLocalDateTime() + " " + Timestamp.valueOf(s).toInstant() + " "
                    + Timestamp.valueOf(s).getNanos());
        }
        t("S null", () -> Timestamp.valueOf((String) null));
        LocalDate[] lds = { LocalDate.of(2024, 1, 15), LocalDate.of(1582, 10, 15), LocalDate.of(1582, 10, 14),
                LocalDate.of(1582, 10, 10), LocalDate.of(1582, 10, 4), LocalDate.of(1, 1, 1), LocalDate.of(0, 1, 1),
                LocalDate.of(-1, 1, 1), LocalDate.of(-5, 3, 1), LocalDate.of(10000, 1, 1), LocalDate.of(1900, 1, 1),
                LocalDate.of(1, 1, 3), LocalDate.of(292278994, 8, 16), LocalDate.of(-292269054, 12, 3),
                LocalDate.of(1500, 2, 28), LocalDate.of(1500, 3, 1), LocalDate.of(2000, 2, 29), LocalDate.of(1600, 2, 29) };
        for (LocalDate d : lds) {
            t("LD " + d, () -> {
                java.sql.Date s = java.sql.Date.valueOf(d);
                return s + " " + s.toLocalDate() + " " + new java.util.Date(s.getTime());
            });
            t("LDT " + d, () -> {
                Timestamp s = Timestamp.valueOf(d.atTime(10, 15, 30, 123456789));
                return s + " " + s.toLocalDateTime() + " " + s.toInstant() + " " + s.getTime();
            });
        }
        for (int i = 0; i < 300; i++) {
            LocalDateTime dt = LocalDateTime.of(random.nextInt(6000) - 2000, 1 + random.nextInt(12),
                    1 + random.nextInt(28), random.nextInt(24), random.nextInt(60), random.nextInt(60),
                    random.nextInt(1000000000));
            t("R " + dt, () -> {
                Timestamp s = Timestamp.valueOf(dt);
                return s + " " + s.toLocalDateTime() + " " + s.toInstant() + " " + java.sql.Date.valueOf(dt.toLocalDate())
                        + " " + Timestamp.from(s.toInstant()).equals(s) + " " + new java.util.Date(s.getTime());
            });
        }
        for (int i = 0; i < 100; i++) {
            LocalTime lt = LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1000000000));
            t("LT " + lt, () -> Time.valueOf(lt) + " " + Time.valueOf(lt).toLocalTime());
        }
        t("LD null", () -> java.sql.Date.valueOf((LocalDate) null));
        t("LT null", () -> Time.valueOf((LocalTime) null));
        t("LDT null", () -> Timestamp.valueOf((LocalDateTime) null));
        t("T toInstant", () -> new Time(0).toInstant());
        t("D toInstant", () -> new java.sql.Date(0).toInstant());
        t("from max", () -> java.util.Date.from(Instant.MAX));
        t("from min", () -> java.util.Date.from(Instant.MIN));
        t("from big", () -> java.util.Date.from(Instant.ofEpochMilli(Long.MAX_VALUE).plusMillis(1)));
        t("from edge", () -> java.util.Date.from(Instant.ofEpochMilli(Long.MAX_VALUE).plusNanos(999999)));
        t("from minedge", () -> java.util.Date.from(Instant.ofEpochMilli(Long.MIN_VALUE)));
        t("from minedge-1", () -> java.util.Date.from(Instant.ofEpochMilli(Long.MIN_VALUE).minusNanos(1)));
        t("from null", () -> java.util.Date.from(null));
        t("from", () -> java.util.Date.from(Instant.ofEpochSecond(1, 999999)).getTime());
        t("TS from max", () -> Timestamp.from(Instant.MAX));
        t("TS from null", () -> Timestamp.from(null));
        t("TS from", () -> Timestamp.from(Instant.parse("2024-01-15T10:15:30.123456789Z")).getNanos());
        t("TS from neg", () -> {
            Timestamp s = Timestamp.from(Instant.parse("1969-12-31T23:59:59.123456789Z"));
            return s + " " + s.getNanos() + " " + s.getTime() + " " + s.toInstant();
        });
        t("TS from minedge", () -> Timestamp.from(Instant.ofEpochMilli(Long.MIN_VALUE)));
        t("TS from maxedge", () -> Timestamp.from(Instant.ofEpochMilli(Long.MAX_VALUE).plusNanos(999999)));
        t("TS from overmax", () -> Timestamp.from(Instant.ofEpochMilli(Long.MAX_VALUE).plusNanos(1000000)));
        t("TS from overmax2", () -> Timestamp.from(Instant.ofEpochSecond(Long.MAX_VALUE / 1000)));
        t("TS from overmax3", () -> Timestamp.from(Instant.ofEpochSecond(Long.MAX_VALUE / 1000 + 1)));
        t("TS from undermin", () -> Timestamp.from(Instant.ofEpochSecond(Long.MIN_VALUE / 1000 - 1)));
        t("TS from undermin3", () -> Timestamp.from(Instant.ofEpochSecond(Long.MIN_VALUE / 1000)));
        java.util.Date d = new java.util.Date(1000);
        Timestamp ts = new Timestamp(1000);
        ts.setNanos(500);
        Timestamp ts2 = new Timestamp(1000);
        Timestamp ts3 = new Timestamp(1500);
        t("equals", () -> d.equals(ts) + " " + ts.equals(d) + " " + d.equals(ts2) + " " + ts2.equals(d) + " "
                + ts2.equals((Object) ts2) + " " + ts.equals(ts2) + " " + ts.equals((Object) null) + " "
                + ts.equals((Timestamp) null) + " " + d.equals(null) + " " + d.equals("x"));
        t("compare", () -> d.compareTo(ts) + " " + ts.compareTo(d) + " " + ts.compareTo(ts2) + " " + ts2.compareTo(ts)
                + " " + d.compareTo(ts3) + " " + ts3.compareTo(d) + " " + ts.compareTo((java.util.Date) ts2) + " "
                + new java.util.Date(1500).compareTo(ts3) + " " + ts3.compareTo(new java.util.Date(1500)) + " "
                + ts3.compareTo(new java.util.Date(1400)) + " " + ts3.compareTo(new java.util.Date(1600)));
        t("before", () -> d.before(ts) + " " + d.after(ts) + " " + ts.before(d) + " " + ts.after(d) + " "
                + ts.before(ts2) + " " + ts.after(ts2) + " " + ts2.before(ts) + " " + d.before(ts3) + " " + ts3.after(d)
                + " " + ts3.after((java.util.Date) new java.util.Date(1400)) + " "
                + ts3.before((java.util.Date) new java.util.Date(1600)));
        t("hash", () -> d.hashCode() + " " + ts.hashCode() + " " + ts2.hashCode() + " " + ts3.hashCode() + " "
                + new Timestamp(-1).hashCode() + " " + new Timestamp(Long.MAX_VALUE).hashCode() + " "
                + new java.util.Date(Long.MIN_VALUE).hashCode());
        t("getTime", () -> ts.getTime() + " " + ts3.getTime() + " " + ts3.getNanos());
        t("setNanos", () -> {
            Timestamp x = new Timestamp(1999);
            x.setNanos(1);
            return x.getTime() + " " + x + " " + x.getNanos();
        });
        t("setNanos neg", () -> {
            Timestamp x = new Timestamp(1999);
            x.setNanos(-1);
            return x;
        });
        t("setNanos big", () -> {
            Timestamp x = new Timestamp(1999);
            x.setNanos(1000000000);
            return x;
        });
        t("setTime", () -> {
            Timestamp x = new Timestamp(1999);
            x.setNanos(5);
            x.setTime(-2500);
            return x.getTime() + " " + x.getNanos() + " " + x;
        });
        t("setTime d", () -> {
            java.util.Date x = new java.util.Date(1999);
            x.setTime(-2500);
            return x.getTime();
        });
        t("classes", () -> java.util.Date.class.getName() + " " + java.sql.Date.class.getName() + " "
                + java.sql.Date.class.getSuperclass().getName() + " " + Timestamp.class.getSuperclass().getName() + " "
                + Time.class.getSuperclass().getName() + " " + java.util.Date.class.getSuperclass().getName() + " "
                + java.util.Date.class.getSuperclass().getSuperclass() + " " + java.util.Date.class.getSimpleName() + " "
                + Timestamp.class.getPackageName() + " " + new Timestamp(0).getClass().getName());
        t("nanos", () -> {
            Timestamp x = new Timestamp(0);
            StringBuilder sb = new StringBuilder();
            for (int n : new int[] { 1, 10, 100, 1000, 123456789, 120000000, 999999999, 100000000, 0 }) {
                x.setNanos(n);
                sb.append(x).append(" | ");
            }
            return sb;
        });
        t("eq ts", () -> {
            Timestamp x = new Timestamp(0);
            x.setNanos(5);
            Timestamp y = new Timestamp(0);
            y.setNanos(5);
            return x.equals(y) + " " + x.equals((Object) y) + " " + x.hashCode() + " " + y.hashCode();
        });
        t("dates eq", () -> new java.sql.Date(5).equals(new java.util.Date(5)) + " "
                + new java.util.Date(5).equals(new java.sql.Date(5)) + " " + new Time(5).equals(new java.sql.Date(5))
                + " " + new java.sql.Date(5).hashCode() + " " + new java.sql.Date(5).compareTo(new java.util.Date(6)));
        t("sql date setTime", () -> {
            java.sql.Date x = new java.sql.Date(5);
            x.setTime(86400000L * 3 + 7);
            return x + " " + x.getTime();
        });
        t("now", () -> Math.abs(new java.util.Date().getTime() - System.currentTimeMillis()) < 1000);
        t("instanceof", () -> {
            Object o = new Timestamp(0);
            return (o instanceof java.util.Date) + " " + (o instanceof java.sql.Date) + " "
                    + (o instanceof Comparable<?>) + " " + (o instanceof java.io.Serializable);
        });
        t("big nanos", () -> {
            Timestamp x = new Timestamp(-1);
            x.setNanos(999999999);
            return x.toInstant() + " " + x.getTime();
        });
        t("compare null", () -> ts.compareTo((Timestamp) null));
        t("d compare null", () -> d.compareTo(null));
        t("d before null", () -> d.before(null));
        t("time of ms", () -> new Time(36930999L).toLocalTime() + " " + new Time(-1).toLocalTime() + " "
                + new Time(86400000L * 400 + 5000).toLocalTime() + " " + new Time(86400000L * 400 + 5000));
    }
}

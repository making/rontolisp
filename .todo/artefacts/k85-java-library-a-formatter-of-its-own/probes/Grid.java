import java.util.*;
import java.time.*;
import java.math.*;

public class Grid {
    static String run(String c, Object... v) {
        try { return String.format(c, v); } catch (RuntimeException e) { return e.getClass().getName() + ": " + e.getMessage(); }
    }
    public static void main(String[] args) {
        String sfx = "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc";
        Object[] vs = { 1234567890123L, -62135769600000L - 86400000L * 400, -1L, LocalDate.of(2024, 2, 29), LocalTime.of(13, 5, 9, 123456789),
            LocalDateTime.of(2024, 2, 29, 0, 5, 9, 7000), ZonedDateTime.of(2024, 7, 1, 23, 59, 59, 999999999, ZoneId.of("Europe/Paris")),
            ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
            ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(5)),
            ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("UTC")),
            ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("GMT+3")),
            OffsetDateTime.of(-5, 3, 1, 1, 2, 3, 0, ZoneOffset.ofHoursMinutes(-3, -30)), OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(-8)),
            Instant.ofEpochSecond(1234567890L, 5), Year.of(-12), YearMonth.of(12345, 12), MonthDay.of(2, 29), DayOfWeek.MONDAY, Month.MAY,
            new Date(0), new Date(-62135769600000L), 5, null, "x", LocalDate.of(12345, 3, 4), LocalDate.of(-12345, 3, 4), LocalDate.of(0, 3, 4), new BigDecimal("1.5") };
        for (Object v : vs) {
            StringBuilder line = new StringBuilder(v == null ? "null" : v.getClass().getName());
            for (char c : sfx.toCharArray()) line.append(" | ").append(run("%t" + c, v));
            System.out.println(line);
            line = new StringBuilder("  T");
            for (char c : sfx.toCharArray()) line.append(" | ").append(run("%T" + c, v));
            System.out.println(line);
        }
        System.out.println(run("%-12tF|%12TB|%Tp|%Tc|%10tH|%-6tk|", 0L, 0L, 0L, null, null, LocalTime.NOON));
        for (String s : new String[]{ "1582-10-04T00:00:00Z", "1582-10-15T00:00:00Z", "1582-12-31T00:00:00Z", "1583-01-01T00:00:00Z", "0001-01-01T00:00:00Z", "2024-12-31T23:59:59.999Z", "1600-12-31T00:00:00Z", "1500-12-31T00:00:00Z", "-0001-12-31T00:00:00Z" }) {
            long m = Instant.parse(s).toEpochMilli();
            System.out.println(s + " " + run("%tj %tF %tc %tC %ty %tA %tl%tp %tN %ts", m, m, m, m, m, m, m, m, m, m));
        }
        Object[] ds = { 0.0, -0.0, 1.0, -1.5, 0.1, 123456.789, 1234567.0, 0.0001, 0.00009999, 0.000123456, 99999.95, 999999.5, 9.9999995, 1e-5, 1e100, -1e-300,
            Double.MIN_VALUE, Double.MAX_VALUE, 4.9e-324 * 3, Double.MIN_NORMAL, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            0.5, 2.5, 0.125, 1.0/3, 2.0/3, 100.0, 1e6, 1e7, 123.0f, 0.1f, Float.MIN_VALUE, Float.MAX_VALUE, new BigDecimal("123.456"), new BigDecimal("-0.000012345"), new BigDecimal("1E+10"), new BigDecimal("0.00"), new BigDecimal("0"), new BigDecimal("99999.95"), new BigDecimal("0.000099995"), new BigInteger("12"),
            5, 5L, null, "x", Double.parseDouble("0x1.08p0"), Double.parseDouble("0x1.18p0"), Double.parseDouble("0x1.28p0"), Double.parseDouble("0x1.0800000000001p0"), Double.parseDouble("0x1.f8p0"), Double.parseDouble("0x1.ff8p0"), Double.parseDouble("0x1.0000000000008p0"), Double.parseDouble("0x1.0000000000018p0"), Double.parseDouble("0x0.0000000000003p-1022"), Double.parseDouble("0x0.8p-1022"), Double.parseDouble("0x0.88p-1022"), Double.parseDouble("0x0.98p-1022"), Double.parseDouble("0x0.0000000000008p-1022"), Double.parseDouble("0x1.fffffffffffffp-1023"), Double.parseDouble("-0x1.18p5"), Float.parseFloat("0x1.08p0"), Float.parseFloat("0x1.000006p0"), Float.MIN_VALUE * 3, Float.MIN_NORMAL };
        String[] gs = { "%g", "%G", "%.0g", "%.1g", "%.2g", "%.3g", "%.10g", "%.17g", "%+g", "% g", "%012g", "%-12g|", "%,g", "%(g", "%,.10g", "%(+012.3g", "%15.2G", "%,(g" };
        String[] as = { "%a", "%A", "%.0a", "%.1a", "%.2a", "%.3a", "%.10a", "%.12a", "%.13a", "%.20a", "%+a", "% a", "%020a", "%-20a|", "%#a", "%+020.3A", "%2$a" };
        for (Object v : ds) {
            StringBuilder line = new StringBuilder(String.valueOf(v));
            for (String g : gs) line.append(" | ").append(run(g, v));
            for (String a : as) line.append(" | ").append(run(a, v));
            System.out.println(line);
        }
        String FLAGS = "-#+ 0,(";
        for (String conv : new String[]{ "g", "G", "a", "A", "tH", "TH", "tq", "Tc", "h", "H", "s" }) {
            for (int set = 0; set < (1 << FLAGS.length()); set++) {
                StringBuilder flags = new StringBuilder();
                for (int i = 0; i < FLAGS.length(); i++) if ((set >> i & 1) != 0) flags.append(FLAGS.charAt(i));
                for (String index : new String[]{ "", "1$" }) for (String width : new String[]{ "", "5" }) for (String precision : new String[]{ "", ".2" }) {
                    Object a = conv.toLowerCase().startsWith("t") ? (Object) 0L : (Object) 1.5;
                    String f = "%" + index + flags + width + precision + conv;
                    String rev = "%" + index + new StringBuilder(flags).reverse() + width + precision + conv;
                    if (conv.equals("h") || conv.equals("H") || conv.equals("s")) {
                        if (!flags.toString().contains("#")) continue;
                    }
                    System.out.println(f + " " + run(f, a) + " / " + run(rev, a));
                }
            }
        }
        for (String f : new String[]{ "%X", "%E", "%.2f", "%.1e", "%5.2e", "%S", "%B", "%C", "%.2s", "%.1b", "%+a", "% a", "%+A", "%(g", "%+g", "%.0a", "%.1g", "%x", "%e", "%c", "%d", "%o" }) {
            System.out.println("null/inf " + f + " [" + run(f, (Object) null) + "] [" + run(f, Double.POSITIVE_INFINITY) + "] [" + run(f, Double.NEGATIVE_INFINITY) + "] [" + run(f, Double.NaN) + "] [" + run(f, "s") + "]");
        }
        System.out.println(run("%s %s", (Object[]) null) + " " + run("%5d|%2$s", 1) + " " + run("%-5d%3$s", 1, 2));
    }
}

import java.util.*;
import java.time.*;
import java.time.format.*;

public class ZoneNames {
    public static void main(String[] args) {
        DateTimeFormatter z = DateTimeFormatter.ofPattern("z", Locale.US);
        int diff = 0, all = 0;
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            for (Instant i : new Instant[]{ Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-07-15T00:00:00Z"), Instant.parse("1950-07-15T00:00:00Z") }) {
                ZonedDateTime t = i.atZone(ZoneId.of(id));
                String a = String.format("%tZ", t), b = z.format(t);
                all++;
                if (!a.equals(b)) { diff++; if (diff < 40) System.out.println(id + " " + i + " fmt=" + a + " dtf=" + b); }
            }
        }
        System.out.println("diff " + diff + " of " + all);
        for (int y : new int[]{ 12345, -12345, 0, -1, 999, 10000 }) {
            LocalDate d = LocalDate.of(y, 3, 4);
            System.out.println(y + " F=" + String.format("%tF", d) + " Y=" + String.format("%tY", d) + " C=" + String.format("%tC", d) + " y=" + String.format("%ty", d) + " c=" + String.format("%tc", d.atStartOfDay(ZoneOffset.UTC)) + " D=" + String.format("%tD", d));
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        for (String s : new String[]{ "1582-10-04T00:00:00Z", "1582-10-15T00:00:00Z", "1582-12-31T00:00:00Z", "1583-01-01T00:00:00Z", "0001-01-01T00:00:00Z", "-0001-12-31T00:00:00Z", "2024-12-31T23:59:59.999Z", "1600-12-31T00:00:00Z", "1500-12-31T00:00:00Z" }) {
            long m = Instant.parse(s).toEpochMilli();
            System.out.println(s + " j=" + String.format("%tj %tF %tc", m, m, m) + " date " + new Date(m));
        }
        System.out.println(String.format("%tN|%tL|%tQ|%ts", -1L, -1L, -1L, -1L));
        System.out.println(String.format("%-12tF|%12TB|%Tp|%TZ|%Tc", 0L, 0L, 0L, 0L, (Object) null));
        System.out.println(String.format("%Tc|%10tH|%-10Tq", (Object) null, (Object) null, null));
    }
}

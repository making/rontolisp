import java.sql.Timestamp;
public class Gap {
    public static void main(String[] a) {
        for (String s : new String[] { "2026-03-08 01:59:59", "2026-03-08 02:00:00", "2026-03-08 02:30:00", "2026-03-08 03:00:00", "2026-11-01 00:59:59", "2026-11-01 01:00:00", "2026-11-01 01:30:00", "2026-11-01 02:00:00", "2026-03-29 01:30:00", "2026-03-29 02:30:00", "2026-10-25 01:30:00", "2026-10-25 02:30:00", "2026-04-05 02:30:00", "2026-04-05 01:30:00", "2026-10-04 02:30:00" }) {
            Timestamp t = Timestamp.valueOf(s);
            System.out.println(s + " -> " + t.getTime() + " " + t + " " + new java.util.Date(t.getTime()).toString().substring(0, 19));
        }
        System.out.println(java.sql.Date.valueOf("2026-03-08").getTime() + " " + java.sql.Time.valueOf("02:30:00").getTime());
    }
}

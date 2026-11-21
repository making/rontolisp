import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SzZoned {
    public static void main(String[] args) {
        System.out.println(String.format("%tc", ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, ZoneId.of("Asia/Tokyo"))));
    }
}

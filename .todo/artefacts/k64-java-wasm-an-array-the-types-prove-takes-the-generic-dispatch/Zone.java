import java.time.ZoneId;
import java.time.ZonedDateTime;

public class Zone {
    public static void main(String[] args) {
        System.out.println(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Paris")).plusHours(1));
    }
}

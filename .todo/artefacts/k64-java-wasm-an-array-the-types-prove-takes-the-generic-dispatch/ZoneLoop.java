import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRules;

public class ZoneLoop {
    public static void main(String[] args) {
        ZoneRules rules = ZoneId.of("Europe/Paris").getRules();
        long h = 0;
        for (int i = 0; i < 2000000; i++) {
            long second = -2000000000L + (long) i * 2000L;
            h = h * 31 + rules.getOffset(Instant.ofEpochSecond(second)).getTotalSeconds();
            h = h * 31 + rules.getOffset(LocalDateTime.ofEpochSecond(second, 0, java.time.ZoneOffset.UTC)).getTotalSeconds();
        }
        System.out.println(h);
    }
}

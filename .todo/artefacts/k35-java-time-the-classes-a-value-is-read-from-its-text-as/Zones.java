import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

// What the rules of the tz database's regions would cost a program that carried them,
// asked of the JDK running this (its public java.time.zone API; no file of it is read):
// how many names, how many sets of rules they share, how many changes of offset those
// hold, and the bytes of one plain encoding -- a change as the seconds since the one
// before it in a variable-length number and the offset it turns to in two bytes, a
// yearly rule in eight, a name as its text and two bytes of which set it has. The
// names whose offset never changes are the ones the library knows (ZoneId).
public class Zones {

    static int varint(long value) {
        int bytes = 1;
        while (value >= 128) {
            value >>= 7;
            bytes++;
        }
        return bytes;
    }

    public static void main(String[] args) {
        TreeSet<String> ids = new TreeSet<>(ZoneId.getAvailableZoneIds());
        Map<String, Integer> sets = new HashMap<>();
        StringBuilder fixed = new StringBuilder();
        long transitions = 0;
        long yearly = 0;
        long bytes = 0;
        int fixedCount = 0;
        for (String id : ids) {
            ZoneRules rules = ZoneId.of(id).getRules();
            bytes += id.length() + 3;
            if (rules.isFixedOffset()) {
                fixed.append(id).append('=').append(rules.getOffset(Instant.EPOCH)).append(' ');
                fixedCount++;
                continue;
            }
            String key = rules.getTransitions().toString() + rules.getTransitionRules();
            if (sets.putIfAbsent(key, sets.size()) == null) {
                long last = Long.MIN_VALUE;
                for (ZoneOffsetTransition transition : rules.getTransitions()) {
                    long second = transition.getInstant().getEpochSecond();
                    bytes += (last == Long.MIN_VALUE ? 6 : varint(second - last)) + 2;
                    last = second;
                }
                transitions += rules.getTransitions().size();
                yearly += rules.getTransitionRules().size();
                bytes += 8L * rules.getTransitionRules().size() + 4;
            }
        }
        System.out.println(ids.size() + " names, " + fixedCount + " of them fixed, " + sets.size()
                + " sets of rules, " + transitions + " changes of offset, " + yearly + " yearly rules, about " + bytes
                + " bytes");
        System.out.println(fixed.toString().trim());
    }
}

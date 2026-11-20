import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

// A walk of every region of the tz database, the same under a JDK and on each backend:
// for each zone its changes of offset and yearly rules, the instants and the local
// date-times on, beside and between every change (those the yearly rules make in five
// later years too), seeded ones anywhere, and a zoned date-time moved across the
// changes. A zone is one line -- its name, how many lines it was asked, and their hash;
// with `REGION=<name>` in the environment the lines of that zone are printed instead.
public class Regions {

    static int hash;

    static int lines;

    static boolean shown;

    static void line(String text) {
        hash = hash * 31 + text.hashCode();
        lines++;
        if (shown) {
            System.out.println("  " + text);
        }
    }

    static String attempt(Supplier<Object> made) {
        try {
            return String.valueOf(made.get());
        }
        catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    static void instant(ZoneId zone, ZoneRules rules, Instant at) {
        line("at " + at.getEpochSecond() + " " + rules.getOffset(at) + " " + rules.getStandardOffset(at) + " "
                + rules.isDaylightSavings(at) + " " + rules.getDaylightSavings(at) + " " + rules.nextTransition(at)
                + " " + rules.previousTransition(at) + " " + ZonedDateTime.ofInstant(at, zone) + " "
                + LocalDateTime.ofInstant(at, zone) + " " + OffsetDateTime.ofInstant(at, zone));
    }

    static void local(ZoneId zone, ZoneRules rules, LocalDateTime at, ZoneOffset preferred) {
        ZonedDateTime zoned = ZonedDateTime.ofLocal(at, zone, preferred);
        line("local " + at + " " + rules.getValidOffsets(at) + " " + rules.getOffset(at) + " "
                + rules.getTransition(at) + " " + rules.isValidOffset(at, preferred) + " " + ZonedDateTime.of(at, zone)
                + " " + zoned + " " + zoned.withEarlierOffsetAtOverlap() + " " + zoned.withLaterOffsetAtOverlap() + " "
                + attempt(() -> ZonedDateTime.ofStrict(at, preferred, zone)) + " "
                + ZonedDateTime.ofInstant(at, preferred, zone) + " " + zoned.with(preferred) + " "
                + at.toLocalDate().atStartOfDay(zone));
    }

    static void moved(ZonedDateTime from, Random random) {
        line("moved " + from + " " + from.plusDays(1) + " " + from.minusDays(1) + " " + from.plusHours(1) + " "
                + from.minusHours(25) + " " + from.plusMonths(6) + " " + from.minusYears(1) + " "
                + from.plusMinutes(90) + " " + from.minusSeconds(5400) + " " + from.plusNanos(7200000000000L) + " "
                + from.plus(Duration.ofHours(36)) + " " + from.minus(Duration.ofMinutes(30)) + " "
                + from.plus(Period.of(0, 1, 1)) + " " + from.minus(Period.ofDays(1)) + " "
                + from.withHour(2).withMinute(30) + " " + from.withDayOfMonth(1) + " "
                + from.truncatedTo(ChronoUnit.DAYS) + " " + from.truncatedTo(ChronoUnit.HOURS) + " "
                + from.plus(12, ChronoUnit.HOURS) + " " + from.plus(1, ChronoUnit.HALF_DAYS) + " "
                + from.minus(1, ChronoUnit.WEEKS) + " " + from.withZoneSameInstant(ZoneOffset.UTC) + " "
                + from.withZoneSameLocal(ZoneId.of("Asia/Kolkata")) + " " + from.toOffsetDateTime() + " "
                + from.toInstant() + " " + from.hashCode());
        ZonedDateTime to = from.plusDays(random.nextInt(400)).plusSeconds(random.nextInt(86400));
        StringBuilder until = new StringBuilder("until ").append(to);
        for (ChronoUnit unit : ChronoUnit.values()) {
            if (unit != ChronoUnit.FOREVER) {
                until.append(' ').append(from.until(to, unit)).append(' ').append(to.until(from, unit));
            }
        }
        line(until.toString());
    }

    static void change(ZoneId zone, ZoneRules rules, ZoneOffsetTransition change, Random random, boolean move) {
        line("change " + change + " " + change.toEpochSecond() + " " + change.getInstant() + " "
                + change.getDateTimeBefore() + " " + change.getDateTimeAfter() + " " + change.getDuration() + " "
                + change.isGap() + " " + change.isOverlap() + " " + change.isValidOffset(change.getOffsetBefore())
                + " " + change.isValidOffset(change.getOffsetAfter()));
        Instant at = change.getInstant();
        instant(zone, rules, at.minusSeconds(1));
        instant(zone, rules, at);
        instant(zone, rules, at.plusNanos(1));
        LocalDateTime before = change.getDateTimeBefore();
        LocalDateTime after = change.getDateTimeAfter();
        long half = change.getDuration().getSeconds() / 2;
        local(zone, rules, before.minusNanos(1), change.getOffsetBefore());
        local(zone, rules, before, change.getOffsetAfter());
        local(zone, rules, before.plusSeconds(half), change.getOffsetAfter());
        local(zone, rules, before.plusSeconds(half), change.getOffsetBefore());
        local(zone, rules, after.minusNanos(1), change.getOffsetBefore());
        local(zone, rules, after, change.getOffsetAfter());
        if (move) {
            moved(ZonedDateTime.ofInstant(at.minusSeconds(random.nextInt(100000)), zone), random);
        }
    }

    public static void main(String[] args) {
        long first = Instant.parse("1800-01-01T00:00:00Z").getEpochSecond();
        long span = Instant.parse("2200-01-01T00:00:00Z").getEpochSecond() - first;
        String only = System.getenv("REGION");
        int total = 0;
        int zones = 0;
        List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(ids);
        for (String id : ids) {
            if (only != null && !only.equals(id)) {
                continue;
            }
            shown = only != null;
            hash = 0;
            lines = 0;
            Random random = new Random(id.hashCode());
            ZoneId zone = ZoneId.of(id);
            ZoneRules rules = zone.getRules();
            List<ZoneOffsetTransition> changes = rules.getTransitions();
            List<ZoneOffsetTransitionRule> yearly = rules.getTransitionRules();
            line(zone + " " + zone.getId() + " " + zone.normalized() + " " + rules + " " + rules.isFixedOffset() + " "
                    + changes.size() + " " + yearly.size() + " " + zone.equals(ZoneId.of(id)) + " "
                    + rules.equals(ZoneId.of(id).getRules()) + " " + (zone.hashCode() == id.hashCode()));
            for (int i = 0; i < changes.size(); i++) {
                change(zone, rules, changes.get(i), random, i % 4 == 0);
            }
            for (ZoneOffsetTransitionRule rule : yearly) {
                line("rule " + rule + " " + rule.getMonth() + " " + rule.getDayOfMonthIndicator() + " "
                        + rule.getDayOfWeek() + " " + rule.getLocalTime() + " " + rule.isMidnightEndOfDay() + " "
                        + rule.getTimeDefinition() + " " + rule.getStandardOffset() + " " + rule.getOffsetBefore()
                        + " " + rule.getOffsetAfter());
                int last = changes.get(changes.size() - 1).getDateTimeBefore().getYear();
                for (int year : new int[] {last, last + 1, 2027, 2040, 2100, 9999, 999999999}) {
                    if (year > last) {
                        change(zone, rules, rule.createTransition(year), random, year < 9999);
                    }
                }
            }
            for (int i = 0; i < 20; i++) {
                Instant at = Instant.ofEpochSecond(first + Math.floorMod(random.nextLong(), span), random.nextInt(2) * 500);
                instant(zone, rules, at);
                local(zone, rules, LocalDateTime.ofEpochSecond(at.getEpochSecond(), 0, ZoneOffset.UTC),
                        ZoneOffset.ofTotalSeconds(random.nextInt(25) * 3600 - 43200));
            }
            for (Instant at : new Instant[] {Instant.MIN, Instant.EPOCH, Instant.MAX}) {
                line("end " + rules.getOffset(at) + " " + rules.getStandardOffset(at) + " " + rules.nextTransition(at)
                        + " " + rules.previousTransition(at));
            }
            for (LocalDateTime at : new LocalDateTime[] {LocalDateTime.MIN, LocalDateTime.MAX}) {
                line("end " + rules.getValidOffsets(at) + " " + rules.getTransition(at) + " "
                        + ZonedDateTime.of(at, zone));
            }
            System.out.println(id + " " + lines + " " + hash);
            total += lines;
            zones++;
        }
        System.out.println(zones + " zones, " + total + " lines");
    }
}

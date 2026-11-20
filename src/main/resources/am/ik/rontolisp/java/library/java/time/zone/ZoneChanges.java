// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The rules of a zone whose offset changes, in numbers: the seconds of its changes in
// order with the offset of the clock and the standard offset before the first and after
// each (a change may be of one of the two alone), and the yearly rules that go on after
// the last change of the clock. A yearly rule counts only past that change, and not at
// all where there is none; an instant's offset is the one the last change at or before
// it turned to, a local date-time's the one of the stretch it is in, or it is in the gap
// or the overlap of one change. All of that, the ends of a gap and of an overlap and
// which neighbour an instant on a change has are a JDK's answers; the hash code is a
// number of ours.
//
// A region of the tz database is read here from the table the compiler made for the
// program (find): the areas the names are shortened by, each ended by a comma, a space,
// then for each set of rules the names that have it, each ended by '!', the rules and a
// space. An area is a name up to its last slash, written in the table as one character
// counted from '#'. A set of rules is numbers in digits of five bits, the lowest first,
// a sixth bit saying more follow: how many offsets the zone has and each of them (in
// quarters of an hour where it is whole ones); which two it starts with, on the clock
// and as its standard; its yearly rules; then its changes, as runs of years one rule
// gives. A rule is a month and a day of the week, a day of the month (counted from the
// end past 31), a time of day (in quarters where it is whole ones) and three offsets:
// before, after and the standard one. A run's rule reads its time from the clock before
// the change, and may change the standard offset alone.
package java.time.zone;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ZoneChanges extends ZoneRules {

    /** The first and the last day a date has, counted from 1970-01-01. */
    private static final long FIRST_DAY = -365243219162L;

    private static final long LAST_DAY = 365241780471L;

    /** The regions read so far and their names: few, so they are looked through. */
    private static String[] names = new String[4];

    private static ZoneRules[] read = new ZoneRules[4];

    private static int count;

    /** What is being read, and where: one region at a time (find). */
    private static String text;

    private static int next;

    /** The places among a zone's offsets of the three the rule read last has. */
    private static int three;

    /** The seconds of the changes, in order. */
    private final long[] at;

    /** The offset of the clock before the first change and after each, in seconds. */
    private final long[] walls;

    /** The standard offset before the first change and after each. */
    private final long[] standards;

    private final Yearly[] yearly;

    /** How many changes there are up to the last one of the clock. */
    private final int clocked;

    private ZoneChanges(long[] at, long[] walls, long[] standards, Yearly[] yearly) {
        super(null);
        this.at = at;
        this.walls = walls;
        this.standards = standards;
        this.yearly = yearly;
        int clocked = at.length;
        while (clocked > 0 && walls[clocked] == walls[clocked - 1]) {
            clocked--;
        }
        this.clocked = clocked;
    }

    /**
     * The rules a program states (ZoneRules.of): the changes of the clock and of the
     * standard offset come as two lists, and are one here.
     */
    static ZoneChanges stated(ZoneOffset baseStandard, ZoneOffset baseWall, List<ZoneOffsetTransition> standards,
            List<ZoneOffsetTransition> transitions, List<ZoneOffsetTransitionRule> rules) {
        Yearly[] yearly = new Yearly[rules.size()];
        for (int i = 0; i < yearly.length; i++) {
            yearly[i] = rules.get(i).yearly;
        }
        int all = standards.size() + transitions.size();
        long[] at = new long[all];
        long[] walls = new long[all + 1];
        long[] kept = new long[all + 1];
        walls[0] = baseWall.getTotalSeconds();
        kept[0] = baseStandard.getTotalSeconds();
        int count = 0;
        int clock = 0;
        int standard = 0;
        while (clock < transitions.size() || standard < standards.size()) {
            long clockAt = clock < transitions.size() ? transitions.get(clock).toEpochSecond() : Long.MAX_VALUE;
            long standardAt = standard < standards.size() ? standards.get(standard).toEpochSecond() : Long.MAX_VALUE;
            at[count] = Math.min(clockAt, standardAt);
            walls[count + 1] = clockAt <= standardAt ? transitions.get(clock++).getOffsetAfter().getTotalSeconds()
                    : walls[count];
            kept[count + 1] = standardAt <= clockAt ? standards.get(standard++).getOffsetAfter().getTotalSeconds()
                    : kept[count];
            count++;
        }
        if (count < all) {
            // a change of both at one second is one change
            long[] fewer = new long[count];
            long[] fewerWalls = new long[count + 1];
            long[] fewerKept = new long[count + 1];
            for (int i = 0; i <= count; i++) {
                fewerWalls[i] = walls[i];
                fewerKept[i] = kept[i];
                if (i < count) {
                    fewer[i] = at[i];
                }
            }
            return new ZoneChanges(fewer, fewerWalls, fewerKept, yearly);
        }
        return new ZoneChanges(at, walls, kept, yearly);
    }

    // ---- the table of the program

    /** The table of the program being compiled, a constant of it. */
    private static native String table0();

    /** A name as the table writes it: its area as the character the head gives it. */
    private static String written(String table, String id) {
        int slash = id.lastIndexOf('/') + 1;
        int head = table.indexOf(' ');
        char area = '#';
        for (int at = 0; slash > 0 && at < head; area++) {
            int end = table.indexOf(',', at);
            if (end - at == slash && table.startsWith(id.substring(0, slash), at)) {
                return area + id.substring(slash);
            }
            at = end + 1;
        }
        return id;
    }

    /** The rules of a region by its name, or null where the program holds none of it. */
    static synchronized ZoneRules find(String id) {
        for (int i = 0; i < count; i++) {
            if (names[i].equals(id)) {
                return read[i];
            }
        }
        String table = table0();
        String name = written(table, id);
        int at = table.indexOf(' ') + 1;
        while (at < table.length()) {
            int end = table.indexOf(' ', at);
            boolean found = false;
            // the names of an entry, each up to its '!'
            for (int mark = table.indexOf('!', at); mark >= 0 && mark < end; mark = table.indexOf('!', at)) {
                found |= mark - at == name.length() && table.startsWith(name, at);
                at = mark + 1;
            }
            if (found) {
                text = table;
                next = at;
                ZoneRules rules = region(end);
                if (count == names.length) {
                    String[] moreNames = new String[count * 2];
                    ZoneRules[] more = new ZoneRules[count * 2];
                    for (int i = 0; i < count; i++) {
                        moreNames[i] = names[i];
                        more[i] = read[i];
                    }
                    names = moreNames;
                    read = more;
                }
                names[count] = id;
                read[count++] = rules;
                return rules;
            }
            at = end + 1;
        }
        return null;
    }

    /** The names of the regions the program holds, added to a set. */
    static void names(Set<String> names) {
        String table = table0();
        int head = table.indexOf(' ');
        List<String> areas = new ArrayList<>();
        for (int at = 0; at < head; at = table.indexOf(',', at) + 1) {
            areas.add(table.substring(at, table.indexOf(',', at)));
        }
        int at = head + 1;
        while (at < table.length()) {
            int rules = table.indexOf(' ', at);
            for (int end = table.indexOf('!', at); end >= 0 && end < rules; end = table.indexOf('!', at)) {
                char first = table.charAt(at);
                names.add(first < 'A' ? areas.get(first - '#') + table.substring(at + 1, end)
                        : table.substring(at, end));
                at = end + 1;
            }
            at = rules + 1;
        }
    }

    private static int number() {
        int value = 0;
        for (int shift = 0;; shift += 5) {
            int digit = text.charAt(next++);
            digit -= digit > '\\' ? 37 : 36;
            value |= (digit & 31) << shift;
            if (digit < 32) {
                return value;
            }
        }
    }

    private static Yearly rule(int clock, long[] offsets) {
        int month = number();
        int day = number();
        int time = number();
        three = number();
        int count = offsets.length;
        return new Yearly(month % 12 + 1, day > 31 ? 31 - day : day, month / 12,
                (time & 1) == 0 ? (time >> 1) * 900 : time >> 1, clock, (int) offsets[three / count / count],
                (int) offsets[three % count], (int) offsets[three / count % count]);
    }

    /** The rules that are written up to a place of the text being read. */
    private static ZoneRules region(int end) {
        long[] offsets = new long[number()];
        for (int i = 0; i < offsets.length; i++) {
            int offset = number();
            int size = (offset >>> 2) ^ -(offset >>> 1 & 1);
            offsets[i] = (offset & 1) == 0 ? size * 900 : size;
        }
        int base = number();
        Yearly[] yearly = new Yearly[number()];
        for (int i = 0; i < yearly.length; i++) {
            yearly[i] = rule(number(), offsets);
        }
        if (next == end && yearly.length == 0) {
            return new ZoneRules(ZoneOffset.ofTotalSeconds((int) offsets[base % offsets.length]));
        }
        // how many changes the runs make, then each: its second, and in the low six bits
        // the places among the offsets of what the clock and the standard turn to
        int runs = next;
        int all = 0;
        while (next < end) {
            all += (number() & 1) == 1 ? 1 : number() + 2;
            number();
            number();
            number();
            number();
        }
        next = runs;
        long[] changes = new long[all];
        all = 0;
        int year = 1800;
        while (next < end) {
            int run = number();
            year += run >> 1;
            int years = (run & 1) == 1 ? 1 : number() + 2;
            Yearly rule = rule(1, offsets);
            for (int of = 0; of < years; of++) {
                changes[all++] = rule.at(year + of) * 64 + three / offsets.length;
            }
        }
        // in the order they come in: the runs are few and each is in order
        for (int i = 1; i < all; i++) {
            long change = changes[i];
            int to = i;
            for (; to > 0 && changes[to - 1] > change; to--) {
                changes[to] = changes[to - 1];
            }
            changes[to] = change;
        }
        long[] walls = new long[all + 1];
        long[] kept = new long[all + 1];
        walls[0] = offsets[base % offsets.length];
        kept[0] = offsets[base / offsets.length];
        for (int i = 0; i < all; i++) {
            int to = (int) (changes[i] & 63);
            walls[i + 1] = offsets[to % offsets.length];
            kept[i + 1] = offsets[to / offsets.length];
            changes[i] >>= 6;
        }
        return new ZoneChanges(changes, walls, kept, yearly);
    }

    // ---- an instant and a local date-time

    /** Which of the seconds, in order, is the last at or before one: -1 for none. */
    private int last(long second) {
        int low = 0;
        int high = at.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (at[middle] <= second) {
                low = middle + 1;
            }
            else {
                high = middle;
            }
        }
        return low - 1;
    }

    /**
     * The change a yearly rule makes that is the last at or before a second, or with
     * next the first after it: its second sixteen times, and which rule; Long.MIN_VALUE
     * where the rules make none there.
     */
    private long ruled(long second, boolean next) {
        if (yearly.length == 0 || clocked == 0) {
            return Long.MIN_VALUE;
        }
        long edge = at[clocked - 1];
        if (!next && second <= edge) {
            return Long.MIN_VALUE;
        }
        long day = Math.floorDiv(second > edge ? second : edge, 86400L);
        int year = LocalDate.ofEpochDay(day < FIRST_DAY ? FIRST_DAY : day > LAST_DAY ? LAST_DAY : day).getYear();
        long found = Long.MIN_VALUE;
        for (int of = year - 1; of <= year + 1 && of <= 999999999; of++) {
            for (int i = 0; i < yearly.length; i++) {
                long changed = yearly[i].at(of);
                if (changed <= edge || (next ? changed <= second : changed > second)) {
                    continue;
                }
                if (found == Long.MIN_VALUE || (next ? changed < found >> 4 : changed > found >> 4)) {
                    found = changed * 16 + i;
                }
            }
        }
        return found;
    }

    /** The offset of the clock of a region's rules at a second of the epoch, in seconds. */
    static int wall(ZoneChanges zone, long second) {
        long rule = zone.ruled(second, false);
        return rule != Long.MIN_VALUE ? zone.yearly[(int) (rule & 15)].after : (int) zone.walls[zone.last(second) + 1];
    }

    /**
     * What a local second is in a region: 0 where it has one offset, the third of the
     * numbers; 1 in the gap and 2 in the overlap of a change, whose second and offsets
     * before and after are the three numbers. The changes that can reach it are within
     * eighteen hours.
     */
    static int placed(ZoneChanges zone, long local, long[] change) {
        long bound = local + 64800;
        while (true) {
            long rule = zone.ruled(bound, false);
            if (rule != Long.MIN_VALUE) {
                Yearly by = zone.yearly[(int) (rule & 15)];
                change[0] = rule >> 4;
                change[1] = by.before;
                change[2] = by.after;
            }
            else {
                int place = zone.last(bound);
                change[2] = zone.walls[place + 1];
                if (place < 0) {
                    return 0;
                }
                change[0] = zone.at[place];
                change[1] = zone.walls[place];
            }
            boolean gap = change[1] < change[2];
            if (local >= change[0] + change[gap ? 2 : 1]) {
                return 0;
            }
            if (local >= change[0] + change[gap ? 1 : 2]) {
                return gap ? 1 : 2;
            }
            bound = change[0] - 1;
        }
    }

    public boolean isFixedOffset() {
        return false;
    }

    public ZoneOffset getOffset(Instant instant) {
        return ZoneOffset.ofTotalSeconds(wall(this, instant.getEpochSecond()));
    }

    public ZoneOffset getOffset(LocalDateTime localDateTime) {
        long[] change = new long[3];
        return ZoneOffset.ofTotalSeconds(
                (int) change[placed(this, localDateTime.toEpochSecond(ZoneOffset.UTC), change) == 0 ? 2 : 1]);
    }

    public List<ZoneOffset> getValidOffsets(LocalDateTime localDateTime) {
        long[] change = new long[3];
        int placed = placed(this, localDateTime.toEpochSecond(ZoneOffset.UTC), change);
        ZoneOffset after = ZoneOffset.ofTotalSeconds((int) change[2]);
        return placed == 0 ? List.of(after)
                : placed == 1 ? List.of() : List.of(ZoneOffset.ofTotalSeconds((int) change[1]), after);
    }

    public boolean isValidOffset(LocalDateTime localDateTime, ZoneOffset offset) {
        long[] change = new long[3];
        int placed = placed(this, localDateTime.toEpochSecond(ZoneOffset.UTC), change);
        return placed != 1 && (offset.getTotalSeconds() == change[2]
                || (placed == 2 && offset.getTotalSeconds() == change[1]));
    }

    public ZoneOffsetTransition getTransition(LocalDateTime localDateTime) {
        long[] change = new long[3];
        return placed(this, localDateTime.toEpochSecond(ZoneOffset.UTC), change) == 0 ? null
                : ZoneOffsetTransition.at(change[0], (int) change[1], (int) change[2]);
    }

    public ZoneOffset getStandardOffset(Instant instant) {
        return ZoneOffset.ofTotalSeconds((int) standards[last(instant.getEpochSecond()) + 1]);
    }

    public Duration getDaylightSavings(Instant instant) {
        long second = instant.getEpochSecond();
        return Duration.ofSeconds(wall(this, second) - standards[last(second) + 1]);
    }

    public boolean isDaylightSavings(Instant instant) {
        long second = instant.getEpochSecond();
        return wall(this, second) != standards[last(second) + 1];
    }

    /** A change a yearly rule makes, or null for none. */
    private ZoneOffsetTransition made(long rule) {
        return rule == Long.MIN_VALUE ? null : yearly[(int) (rule & 15)].made(rule >> 4);
    }

    /** The change of the clock that is the first listed from a place on, or back from it. */
    private ZoneOffsetTransition listed(int place, int step) {
        while (place >= 0 && place < clocked && walls[place] == walls[place + 1]) {
            place += step;
        }
        return place < 0 || place >= clocked ? null
                : ZoneOffsetTransition.at(at[place], (int) walls[place], (int) walls[place + 1]);
    }

    public ZoneOffsetTransition nextTransition(Instant instant) {
        long second = instant.getEpochSecond();
        ZoneOffsetTransition listed = listed(last(second) + 1, 1);
        return listed != null ? listed : made(ruled(second, true));
    }

    public ZoneOffsetTransition previousTransition(Instant instant) {
        // a change is on a whole second: one on the instant's own is before a later nano
        long second = instant.getNano() > 0 ? instant.getEpochSecond() : instant.getEpochSecond() - 1;
        long rule = ruled(second, false);
        return rule != Long.MIN_VALUE ? made(rule) : listed(last(second) < clocked ? last(second) : clocked - 1, -1);
    }

    public List<ZoneOffsetTransition> getTransitions() {
        List<ZoneOffsetTransition> changes = new ArrayList<>();
        for (int i = 0; i < clocked; i++) {
            if (walls[i] != walls[i + 1]) {
                changes.add(listed(i, 1));
            }
        }
        return List.copyOf(changes);
    }

    public List<ZoneOffsetTransitionRule> getTransitionRules() {
        List<ZoneOffsetTransitionRule> rules = new ArrayList<>();
        for (Yearly rule : yearly) {
            rules.add(new ZoneOffsetTransitionRule(rule));
        }
        return List.copyOf(rules);
    }

    private static boolean same(long[] a, long[] b) {
        boolean same = a.length == b.length;
        for (int i = 0; same && i < a.length; i++) {
            same = a[i] == b[i];
        }
        return same;
    }

    public boolean equals(Object other) {
        if (!(other instanceof ZoneChanges that) || !same(that.at, at) || !same(that.walls, walls)
                || !same(that.standards, standards) || that.yearly.length != yearly.length) {
            return false;
        }
        for (int i = 0; i < yearly.length; i++) {
            if (!that.yearly[i].same(yearly[i])) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        int hash = at.length * 31 + yearly.length;
        for (int i = 0; i < at.length; i++) {
            hash = hash * 31 + Long.hashCode(at[i] + walls[i + 1]);
        }
        return hash * 31 + Long.hashCode(walls[0] ^ standards[at.length] << 20);
    }

    public String toString() {
        return "ZoneRules[currentStandardOffset=" + ZoneOffset.ofTotalSeconds((int) standards[at.length]).getId()
                + "]";
    }
}

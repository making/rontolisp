// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The names of zones in words in the four kinds of locale the library tells apart
// (Texts.kind), as a JDK writes and reads them: of the regions a program holds, the tables
// the compiler gives it (written0, read0; .kb/java-frontend.md, "java.time: the names"),
// and of UTC, GMT and the fixed zones of the tz database, rules. A region's entry holds,
// for each kind, its names of standard time, daylight time and the zone as a whole, short
// and in full, and the spans of seconds where a JDK writes the name of the other time
// than its rules say.
// Third-party: Unicode CLDR data (Unicode-3.0), as a running JDK writes it, Copyright (c)
// 1991-2026 Unicode, Inc. Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package java.time.format;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

final class ZoneNames {

    private ZoneNames() {
    }

    /**
     * The names the program writes for the regions it holds: the groups of six names a
     * kind of locale writes for a region, each once, separated by \u0001, ended by
     * \u0002, the last by \u0003; then each region's id, the number of its group of each
     * kind (the root, English, British, Canadian) and its spans, separated by \u0001,
     * ended by \u0002. A name is empty where English has the same, but the root's of
     * standard and daylight time in full, empty where they are its city's marked.
     */
    private static native String written0();

    /**
     * The names the program reads as the regions it holds: each region's id, then its
     * names, each the kinds of locale it is read in (a mask after '@'), the style (0
     * short, 1 full) and the time (0 standard, 1 daylight, 2 generic; 3 a city of the
     * root's, read as it is, with " (+0)" and with " (+1)") before the name, separated by
     * \u0001, ended by \u0002.
     */
    private static native String read0();

    /**
     * The entries of the regions the program holds and the groups of names they number,
     * made the first time a name is asked.
     */
    private static String[][] written;

    private static String[][] groups;

    /**
     * The names read, the zones they are read as, and for each the kinds of locale it is
     * read in (a mask), its style and its time, side by side.
     */
    private static String[] readNames;

    private static String[] readZones;

    private static int[] readKeys;

    private static int[] readTimes;

    /** The fields of a table's entry. */
    private static String[] fields(String text, int from, int to) {
        int count = 1;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\u0001') {
                count++;
            }
        }
        String[] fields = new String[count];
        int at = from;
        for (int i = 0; i < count; i++) {
            int end = text.indexOf('\u0001', at);
            end = end < 0 || end > to ? to : end;
            fields[i] = text.substring(at, end);
            at = end + 1;
        }
        return fields;
    }

    /** The entries of a table, from one index to another, each ended by \u0002. */
    private static String[][] entries(String text, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\u0002') {
                count++;
            }
        }
        String[][] entries = new String[count][];
        for (int at = from, n = 0; at < to; n++) {
            int end = text.indexOf('\u0002', at);
            entries[n] = fields(text, at, end);
            at = end + 1;
        }
        return entries;
    }

    /**
     * A region's entry: its id, the numbers of its groups of names and its spans; or null
     * where it is not held.
     */
    private static String[] written(String id) {
        String[][] table = written;
        if (table == null) {
            String text = written0();
            int split = text.indexOf('\u0003');
            groups = entries(text, 0, split);
            table = entries(text, split + 1, text.length());
            written = table;
        }
        for (String[] entry : table) {
            if (entry[0].equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** The number the digits of a text from one index to another are, a minus before them. */
    private static long number(String text, int from, int to) {
        boolean negative = text.charAt(from) == '-';
        long value = 0;
        for (int i = negative ? from + 1 : from; i < to; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return negative ? -value : value;
    }

    private static void read() {
        if (readNames != null) {
            return;
        }
        // UTC's name in full is read as Etc/UTC in English
        String text = "Etc/UTC\u0001N12Coordinated Universal Time\u0002" + read0();
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            // a city of the root's is three names
            count += text.charAt(i) != '\u0001' ? 0 : text.charAt(i + 3) == '3' ? 3 : 1;
        }
        String[] names = new String[count];
        String[] zones = new String[count];
        int[] keys = new int[count];
        int[] times = new int[count];
        int n = 0;
        for (int at = 0; at < text.length();) {
            int end = text.indexOf('\u0002', at);
            String[] entry = fields(text, at, end);
            for (int i = 1; i < entry.length; i++) {
                String read = entry[i];
                int key = (read.charAt(0) - '@') << 1 | (read.charAt(1) - '0');
                int time = read.charAt(2) - '0';
                for (int part = time == 3 ? 0 : time; part <= (time == 3 ? 2 : time); part++) {
                    names[n] = read.substring(3) + (time != 3 || part == 2 ? "" : part == 0 ? " (+0)" : " (+1)");
                    zones[n] = entry[0];
                    keys[n] = key;
                    times[n++] = part;
                }
            }
            at = end + 1;
        }
        readZones = zones;
        readKeys = keys;
        readTimes = times;
        readNames = names;
    }

    /** A fixed zone's name: UTC's, GMT's, a fixed zone of the tz database's; or null. */
    private static String fixed(String id, int kind, boolean full) {
        String name = id.startsWith("Etc/") ? id.substring(4) : id;
        if (name.equals("UTC") || name.equals("UCT") || name.equals("Universal") || name.equals("Zulu")) {
            return full ? (kind == 0 ? "GMT" : "Coordinated Universal Time") : "UTC";
        }
        if (name.equals("GMT") || name.equals("GMT0") || name.equals("Greenwich") || name.equals("GMT+0")
                || name.equals("GMT-0")) {
            return full && kind != 0 ? "Greenwich Mean Time" : "GMT";
        }
        if (id.startsWith("Etc/GMT+") || id.startsWith("Etc/GMT-")) {
            // Etc/GMT+5 is five hours behind Greenwich
            String hours = id.substring(8);
            return "GMT" + (id.charAt(7) == '+' ? "-" : "+") + (hours.length() < 2 ? "0" : "") + hours + ":00";
        }
        return null;
    }

    /**
     * A zone's name in a kind of locale, short or in full, of a time (0 standard, 1
     * daylight, 2 the zone as a whole); null for a zone with none.
     */
    static String name(String id, int kind, boolean full, int time) {
        String[] entry = written(id);
        if (entry == null) {
            return fixed(id, kind, full);
        }
        int place = time * 2 + (full ? 1 : 0);
        String name = names(entry, kind)[place];
        if (!name.isEmpty()) {
            return name;
        }
        if (kind == 0 && full && time < 2) {
            // the root's names of standard and daylight time: its city's, marked
            return names(entry, 0)[5] + (time == 0 ? " (+0)" : " (+1)");
        }
        return names(entry, 1)[place];
    }

    /** The six names of a region's entry in a kind of locale: its group of them. */
    private static String[] names(String[] entry, int kind) {
        String number = entry[1 + kind];
        return groups[(int) number(number, 0, number.length())];
    }

    /**
     * Whether a JDK writes the name of a zone's daylight time at a second: where its
     * rules say so, but in the spans its entry holds.
     */
    static boolean daylight(ZoneId zone, long second) {
        boolean daylight = zone.getRules().isDaylightSavings(Instant.ofEpochSecond(second));
        String[] entry = written(zone.getId());
        String spans = entry == null ? "" : entry[5];
        for (int at = 0; at < spans.length();) {
            int comma = spans.indexOf(',', at);
            int end = spans.indexOf(',', comma + 1);
            end = end < 0 ? spans.length() : end;
            if (second >= number(spans, at, comma) && (end == comma + 1 || second < number(spans, comma + 1, end))) {
                return !daylight;
            }
            at = end + 1;
        }
        return daylight;
    }

    /**
     * The longest name in words standing at a position, in the reading's kind of locale,
     * short or in full: its length, the zone it is read as and its time through the
     * arrays given; 0 for none. Of two as long the first is taken; a zone preferred that
     * writes the name is read in place of the one a JDK reads it as.
     */
    static int read(Reading in, int at, boolean full, Set<ZoneId> preferred, String[] zone, int[] time) {
        read();
        int style = full ? 1 : 0;
        int length = 0;
        for (int i = 0; i < readNames.length; i++) {
            String name = readNames[i];
            int key = readKeys[i];
            if ((key & 1) == style && (key >> 1 + in.texts & 1) != 0 && name.length() > length
                    && in.matches(at, name)) {
                length = name.length();
                zone[0] = readZones[i];
                time[0] = readTimes[i];
            }
        }
        if (length > 0 && preferred != null) {
            for (ZoneId candidate : preferred) {
                for (int written = 0; written < 3; written++) {
                    String name = name(candidate.getId(), in.texts, full, written);
                    if (name != null && name.length() == length && in.matches(at, name)) {
                        zone[0] = candidate.getId();
                        return length;
                    }
                }
            }
        }
        return length;
    }
}

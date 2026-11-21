import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

// Every template of bounded counts, asked of a JDK 25 in the five locales Locale has: the
// dates (each field to its most, the year to 6, the week to 3), the times (a period to its
// most, every pair of hour letters, every zone of v and z to its most), every date some
// locale resolves before every time some locale resolves, and seeded random ones. The
// patterns some locale has are printed, the rest as one hash per locale.
public class Every {

    static final Locale[] LOCALES = {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA, Locale.ENGLISH};

    static String pattern(String template, Locale locale) {
        try {
            return DateTimeFormatterBuilder.getLocalizedDateTimePattern(template, IsoChronology.INSTANCE, locale)
                .replace('\u202f', '_');
        }
        catch (RuntimeException e) {
            return "!" + e.getMessage();
        }
    }

    static String repeated(char letter, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(letter);
        }
        return text.toString();
    }

    /** Prints the templates some locale has a pattern for; a hash per locale of every answer. */
    static List<String> asked(String what, List<String> templates, boolean listed) {
        List<String> resolved = new ArrayList<>();
        int[] hashes = new int[LOCALES.length];
        int[] counts = new int[LOCALES.length];
        for (String template : templates) {
            StringBuilder line = new StringBuilder(template);
            boolean any = false;
            for (int i = 0; i < LOCALES.length; i++) {
                String answer = pattern(template, LOCALES[i]);
                hashes[i] = hashes[i] * 31 + answer.hashCode();
                boolean found = !answer.startsWith("!");
                counts[i] += found ? 1 : 0;
                any |= found;
                line.append(" | ").append(found ? answer : "-");
            }
            if (any) {
                resolved.add(template);
                if (listed) {
                    System.out.println(line);
                }
            }
        }
        StringBuilder sum = new StringBuilder(what + " " + templates.size() + ":");
        for (int i = 0; i < LOCALES.length; i++) {
            sum.append(' ').append(counts[i]).append('/').append(hashes[i]);
        }
        System.out.println(sum);
        return resolved;
    }

    static List<String> dates() {
        List<String> templates = new ArrayList<>();
        for (int g = 0; g <= 5; g++) {
            for (int y = 0; y <= 6; y++) {
                for (int q = 0; q <= 5; q++) {
                    for (int m = 0; m <= 5; m++) {
                        for (int w = 0; w <= 3; w++) {
                            for (int e = 0; e <= 5; e++) {
                                for (int d = 0; d <= 2; d++) {
                                    templates.add(repeated('G', g) + repeated('y', y) + repeated('Q', q) + repeated('M', m)
                                            + repeated('w', w) + repeated('E', e) + repeated('d', d));
                                }
                            }
                        }
                    }
                }
            }
        }
        return templates;
    }

    static List<String> zones() {
        List<String> zones = new ArrayList<>();
        zones.add("");
        for (int n = 1; n <= 4; n++) {
            for (int bits = 0; bits < (1 << n); bits++) {
                StringBuilder zone = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    zone.append((bits >> i & 1) == 0 ? 'v' : 'z');
                }
                zones.add(zone.toString());
            }
        }
        return zones;
    }

    static List<String> times() {
        List<String> hours = new ArrayList<>();
        hours.add("");
        for (char a : "hHjC".toCharArray()) {
            hours.add(String.valueOf(a));
            for (char b : "hHjC".toCharArray()) {
                hours.add("" + a + b);
            }
        }
        List<String> templates = new ArrayList<>();
        for (int b = 0; b <= 5; b++) {
            for (String hour : hours) {
                for (int m = 0; m <= 2; m++) {
                    for (int s = 0; s <= 2; s++) {
                        for (String zone : zones()) {
                            templates.add(repeated('B', b) + hour + repeated('m', m) + repeated('s', s) + zone);
                        }
                    }
                }
            }
        }
        return templates;
    }

    static long seed = 77;

    static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    static List<String> random() {
        List<String> templates = new ArrayList<>();
        String order = "GyQMwEdB";
        int[] most = {6, 30, 6, 6, 30, 6, 3, 6};
        for (int i = 0; i < 60000; i++) {
            StringBuilder template = new StringBuilder();
            for (int f = 0; f < order.length(); f++) {
                if (next(2) == 0) {
                    template.append(repeated(order.charAt(f), 1 + next(next(3) == 0 ? most[f] : 5)));
                }
            }
            int hours = next(3);
            for (int k = 0; k < hours; k++) {
                template.append("hHjCjC".charAt(next(6)));
            }
            template.append(repeated('m', next(3))).append(repeated('s', next(3)));
            int zones = next(4);
            for (int k = 0; k < zones; k++) {
                template.append("vvz".charAt(next(3)));
            }
            templates.add(template.toString());
        }
        String letters = "GyQMwEdBhHjCmsvzLcaxuW";
        for (int i = 0; i < 20000; i++) {
            StringBuilder template = new StringBuilder();
            int length = 1 + next(12);
            for (int k = 0; k < length; k++) {
                template.append(letters.charAt(next(letters.length())));
            }
            templates.add(template.toString());
        }
        return templates;
    }

    public static void main(String[] args) {
        List<String> dates = asked("dates", dates(), true);
        List<String> times = asked("times", times(), true);
        List<String> joined = new ArrayList<>();
        for (String date : dates) {
            for (String time : times) {
                joined.add(date + time);
            }
        }
        asked("joined", joined, true);
        asked("random", random(), true);
    }
}

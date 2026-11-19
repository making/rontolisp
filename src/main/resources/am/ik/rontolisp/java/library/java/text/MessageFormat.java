// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A pattern of text and arguments {n}. What the specification leaves to the
// implementation -- the refusals' words, the largest index, the quotes toPattern writes,
// how a number with no format type prints -- is a JDK 25's answers to a grid of patterns
// and arguments (.kb/java-frontend.md, "java.text.MessageFormat").
package java.text;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MessageFormat {

    /** The format types a JDK knows, by their lower-case names: each refused by name. */
    private static final String[] TYPES = {"number", "date", "time", "choice", "list", "dtf_date", "dtf_time",
        "dtf_datetime", "basic_iso_date", "iso_date", "iso_date_time", "iso_instant", "iso_local_date",
        "iso_local_date_time", "iso_local_time", "iso_offset_date", "iso_offset_date_time", "iso_offset_time",
        "iso_ordinal_date", "iso_time", "iso_week_date", "iso_zoned_date_time", "rfc_1123_date_time"};

    /** One more than the largest argument index a pattern may name. */
    private static final int INDEXES = 10000;

    private Locale locale;

    /** The text before each argument, and after the last: one more than the arguments. */
    private String[] texts;

    /** The index of each argument, in the pattern's order. */
    private int[] indexes;

    public MessageFormat(String pattern) {
        this(pattern, Locale.getDefault());
    }

    public MessageFormat(String pattern, Locale locale) {
        this.locale = locale;
        applyPattern(pattern);
    }

    public Locale getLocale() {
        return this.locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public static String format(String pattern, Object... arguments) {
        return new MessageFormat(pattern).format(arguments);
    }

    /**
     * Reads a pattern. One that is refused leaves the format as it was.
     */
    public void applyPattern(String pattern) {
        ArrayList<String> texts = new ArrayList<>();
        ArrayList<Integer> indexes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i++);
            if (c == '\'') {
                if (i < pattern.length() && pattern.charAt(i) == '\'') {
                    text.append(c);
                    i++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (c == '{' && !quoted) {
                // the index, the type and the style, separated by the first two commas a
                // quote does not hold; a brace in them nests
                StringBuilder[] parts = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
                int part = 0;
                int depth = 0;
                boolean held = false;
                while (true) {
                    if (i == pattern.length()) {
                        throw new IllegalArgumentException("Unmatched braces in the pattern.");
                    }
                    char d = pattern.charAt(i++);
                    if (d == '\'') {
                        held = !held;
                    }
                    else if (held) {
                        // as it is
                    }
                    else if (d == '}' && depth == 0) {
                        break;
                    }
                    else if (d == ',' && part < 2) {
                        part++;
                        continue;
                    }
                    else if (d == ' ' && part == 1 && parts[1].length() == 0) {
                        continue;
                    }
                    else if (d == '{') {
                        depth++;
                    }
                    else if (d == '}') {
                        depth--;
                    }
                    parts[part].append(d);
                }
                indexes.add(index(parts[0].toString()));
                String type = parts[1].toString();
                if (!type.trim().isEmpty()) {
                    String name = type.trim().toLowerCase(Locale.ROOT);
                    for (String known : TYPES) {
                        if (known.equals(name)) {
                            throw new UnsupportedOperationException("the format type " + type.trim()
                                    + " of MessageFormat is not supported by the experimental Java subset yet");
                        }
                    }
                    throw new IllegalArgumentException("unknown format type: " + type);
                }
                texts.add(text.toString());
                text.setLength(0);
            }
            else {
                text.append(c);
            }
        }
        texts.add(text.toString());
        this.texts = texts.toArray(new String[0]);
        this.indexes = new int[indexes.size()];
        for (int k = 0; k < this.indexes.length; k++) {
            this.indexes[k] = indexes.get(k);
        }
    }

    private static int index(String digits) {
        int index;
        try {
            index = Integer.parseInt(digits);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("can't parse argument number: " + digits);
        }
        if (index < 0) {
            throw new IllegalArgumentException("negative argument number: " + index);
        }
        if (index >= INDEXES) {
            throw new IllegalArgumentException(index + " exceeds the ArgumentIndex implementation limit");
        }
        return index;
    }

    /**
     * The pattern read back: a brace of the text quoted, a run of them in one quote, a
     * quote doubled.
     */
    public String toPattern() {
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < this.texts.length; i++) {
            boolean quoted = false;
            String text = this.texts[i];
            for (int k = 0; k < text.length(); k++) {
                char c = text.charAt(k);
                if (c == '{') {
                    if (!quoted) {
                        pattern.append('\'');
                        quoted = true;
                    }
                    pattern.append(c);
                }
                else if (c == '\'') {
                    pattern.append("''");
                }
                else {
                    if (quoted) {
                        pattern.append('\'');
                        quoted = false;
                    }
                    pattern.append(c);
                }
            }
            if (quoted) {
                pattern.append('\'');
            }
            if (i < this.indexes.length) {
                pattern.append('{').append(this.indexes[i]).append('}');
            }
        }
        return pattern.toString();
    }

    /**
     * The text of the pattern with each argument's in its place; an index past the
     * arguments is left as it was written.
     */
    public final String format(Object arguments) {
        Object[] values = (Object[]) arguments;
        StringBuilder text = new StringBuilder(this.texts[0]);
        for (int i = 0; i < this.indexes.length; i++) {
            int index = this.indexes[i];
            if (values == null || index >= values.length) {
                text.append('{').append(index).append('}');
            }
            else {
                Object value = values[index];
                text.append(value instanceof Number number ? number(number) : String.valueOf(value));
            }
            text.append(this.texts[i + 1]);
        }
        return text.toString();
    }

    /** A number as the root and English locales write one: grouped by three. */
    private String number(Number number) {
        if (this.locale == null) {
            throw new NullPointerException();
        }
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte
                || number instanceof AtomicInteger || number instanceof AtomicLong) {
            return grouped(Long.toString(number.longValue()));
        }
        return decimal(number.doubleValue());
    }

    /**
     * A double rounded half to even at three digits after the point, those that are 0 left
     * out. The digits are the shortest that read back as it, but for two rules a probe
     * found: a tie in them is decided by the double's own value, and a whole number from
     * 2^53 to 2^63 is its own digits with the last one or two rounded away as its
     * exponent grows.
     */
    private static String decimal(double x) {
        if (x != x) {
            return "NaN";
        }
        long bits = Double.doubleToRawLongBits(x);
        String sign = bits < 0 ? "-" : "";
        double a = Math.abs(x);
        if (a == Double.POSITIVE_INFINITY) {
            return sign + "\u221E";
        }
        int exponent = (int) ((bits >>> 52) & 0x7FF) - 1023;
        if (exponent >= 53 && exponent <= 62) {
            long whole = (long) a;
            long unit = exponent >= 61 ? 100 : exponent >= 58 ? 10 : 1;
            long kept = whole / unit;
            if (whole % unit * 2 >= unit && unit > 1) {
                kept++;
            }
            return sign + grouped(Long.toString(kept * unit));
        }
        // the shortest digits: n / 10^f, n with no 0 last while f > 0
        String shortest = Double.toString(a);
        long n = 0;
        int f = 0;
        boolean after = false;
        int i = 0;
        for (; i < shortest.length() && shortest.charAt(i) != 'E'; i++) {
            char c = shortest.charAt(i);
            if (c == '.') {
                after = true;
            }
            else {
                n = n * 10 + (c - '0');
                f += after ? 1 : 0;
            }
        }
        if (i < shortest.length()) {
            f -= Integer.parseInt(shortest.substring(i + 1));
        }
        while (f > 0 && n % 10 == 0) {
            n /= 10;
            f--;
        }
        if (exponent > 62) {
            return sign + grouped(n + "0".repeat(-f));
        }
        // below 2^53, so a thousand times it is a long
        long thousandths = 0;
        if (f <= 3) {
            thousandths = n * ten(3 - f);
        }
        else if (f - 3 < 19) {
            long unit = ten(f - 3);
            long q = n / unit;
            long r = n % unit;
            int c = Long.compare(r, unit / 2);
            if (c == 0) {
                // a tie, so n ends in its one 5 and f is 4: n / 10^4 against the double,
                // m * 2^-j, is m * 625 against n * 2^(j - 4)
                long m = (bits & 0xFFFFFFFFFFFFFL) | 0x10000000000000L;
                int j = 1075 - (exponent + 1023);
                c = Long.compare(m * 625, n << (j - 4));
            }
            thousandths = q + (c > 0 || c == 0 && q % 2 == 1 ? 1 : 0);
        }
        String text = sign + grouped(Long.toString(thousandths / 1000));
        int fraction = (int) (thousandths % 1000);
        if (fraction == 0) {
            return text;
        }
        String three = Integer.toString(fraction + 1000);
        int end = 4;
        while (three.charAt(end - 1) == '0') {
            end--;
        }
        return text + "." + three.substring(1, end);
    }

    private static long ten(int power) {
        long ten = 1;
        for (int i = 0; i < power; i++) {
            ten *= 10;
        }
        return ten;
    }

    /** Digits with a comma before each three from the right, past a sign. */
    private static String grouped(String digits) {
        int from = digits.startsWith("-") ? 1 : 0;
        StringBuilder text = new StringBuilder(digits.substring(0, from));
        for (int i = from; i < digits.length(); i++) {
            if (i > from && (digits.length() - i) % 3 == 0) {
                text.append(',');
            }
            text.append(digits.charAt(i));
        }
        return text.toString();
    }
}

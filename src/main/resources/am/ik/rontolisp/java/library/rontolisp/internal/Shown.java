// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// How a failed assertion shows a value, and the comparisons of elements its checks are
// made of, as AssertJ 3.27 shows them under a JDK: a text (any CharSequence) between
// quotes, a long and a float with their letters, a class by its canonical name, a path
// as it reads, a file by its absolute path, a duration without its "PT", the local and
// zoned dates and times followed by their class; the elements of an array, a collection
// and a map each shown the same way, a map's in the order of its keys, those of a
// collection or an array of objects on lines of their own past 80 characters.
public final class Shown {

    // The longest a collection is shown on one line.
    private static final int ONE_LINE = 80;

    private Shown() {
    }

    public static String of(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            return "\"" + text + "\"";
        }
        if (value instanceof Character) {
            return "'" + value + "'";
        }
        if (value instanceof Long) {
            return value + "L";
        }
        if (value instanceof Float) {
            return value + "f";
        }
        if (value instanceof Class<?> type) {
            String name = type.getCanonicalName();
            return name == null ? type.getName() : name;
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder text = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : sorted(map)) {
                text.append(first ? "" : ", ").append(of(entry.getKey())).append('=').append(of(entry.getValue()));
                first = false;
            }
            return text.append('}').toString();
        }
        if (value instanceof Map.Entry<?, ?> entry) {
            return of(entry.getKey()) + "=" + of(entry.getValue());
        }
        if (value instanceof java.nio.file.Path) {
            return value.toString();
        }
        if (value instanceof Iterable<?> elements) {
            return wrapped(listOf(elements));
        }
        List<Object> elements = elementsOf(value);
        if (elements == null) {
            return known(value);
        }
        return value instanceof Object[] ? wrapped(elements) : ofAll(elements);
    }

    // A value of a class of the JDK's that a failure shows otherwise than as its text.
    private static String known(Object value) {
        switch (value.getClass().getName()) {
            case "java.io.File":
                return ((java.io.File) value).getAbsolutePath();
            case "java.time.Duration":
                return value.toString().substring(2);
            case "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime",
                    "java.time.ZonedDateTime":
                return value + " (" + value.getClass().getName() + ")";
            default:
                return String.valueOf(value);
        }
    }

    // A map's entries in the order of their keys where the keys have one, else in the
    // order the map gives them (AssertJ 3.27 under a JDK: keys of two kinds or a null
    // key keep the map's order).
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<Map.Entry<?, ?>> sorted(Map<?, ?> map) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        try {
            entries.sort((one, other) -> ((Comparable) one.getKey()).compareTo(other.getKey()));
        }
        catch (RuntimeException unordered) {
            return new ArrayList<>(map.entrySet());
        }
        return entries;
    }

    public static String ofAll(List<?> elements) {
        StringBuilder text = new StringBuilder("[");
        boolean first = true;
        for (Object element : elements) {
            text.append(first ? "" : ", ").append(of(element));
            first = false;
        }
        return text.append(']').toString();
    }

    // Elements on one line, or on lines of their own where one would be too long.
    private static String wrapped(List<?> elements) {
        String line = ofAll(elements);
        if (line.length() <= ONE_LINE || elements.size() < 2) {
            return line;
        }
        StringBuilder text = new StringBuilder("[");
        boolean first = true;
        for (Object element : elements) {
            text.append(first ? "" : ",\n    ").append(of(element));
            first = false;
        }
        return text.append(']').toString();
    }

    // A value on the line under a heading, two spaces in.
    public static String under(Object value) {
        return "  " + of(value);
    }

    // An expected value and the actual one, each after its heading on its line, or both
    // under them where either has several lines.
    public static String expected(Object expected, Object actual) {
        String one = of(expected);
        String other = of(actual);
        if (one.indexOf('\n') < 0 && other.indexOf('\n') < 0) {
            return "\nexpected: " + one + "\n but was: " + other;
        }
        return "\nexpected: \n  " + one.replace("\n", "\n  ") + "\n but was: \n  " + other.replace("\n", "\n  ");
    }

    public static List<Object> listOf(Iterable<?> elements) {
        List<Object> list = new ArrayList<>();
        Iterator<?> each = elements.iterator();
        while (each.hasNext()) {
            list.add(each.next());
        }
        return list;
    }

    // The elements of an array of any element type, or null for a value that is none.
    public static List<Object> elementsOf(Object value) {
        List<Object> list = new ArrayList<>();
        if (value instanceof Object[] array) {
            for (Object each : array) {
                list.add(each);
            }
        }
        else if (value instanceof int[] array) {
            for (int each : array) {
                list.add(each);
            }
        }
        else if (value instanceof byte[] array) {
            for (byte each : array) {
                list.add(each);
            }
        }
        else if (value instanceof long[] array) {
            for (long each : array) {
                list.add(each);
            }
        }
        else if (value instanceof char[] array) {
            for (char each : array) {
                list.add(each);
            }
        }
        else if (value instanceof double[] array) {
            for (double each : array) {
                list.add(each);
            }
        }
        else if (value instanceof boolean[] array) {
            for (boolean each : array) {
                list.add(each);
            }
        }
        else if (value instanceof short[] array) {
            for (short each : array) {
                list.add(each);
            }
        }
        else if (value instanceof float[] array) {
            for (float each : array) {
                list.add(each);
            }
        }
        else {
            return null;
        }
        return list;
    }

    // Whether two values are equal: two arrays by their elements.
    public static boolean same(Object one, Object other) {
        if (one == other) {
            return true;
        }
        if (one == null || other == null) {
            return false;
        }
        List<Object> first = elementsOf(one);
        if (first != null) {
            List<Object> second = elementsOf(other);
            if (second == null || first.size() != second.size()) {
                return false;
            }
            for (int i = 0; i < first.size(); i++) {
                if (!same(first.get(i), second.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return one.equals(other);
    }

    public static boolean holds(List<?> elements, Object value) {
        for (Object element : elements) {
            if (same(element, value)) {
                return true;
            }
        }
        return false;
    }

    // The elements of one list that another does not hold, each as often as it is over.
    public static List<Object> without(List<?> elements, List<?> taken) {
        List<Object> left = new ArrayList<>(elements);
        for (Object each : taken) {
            for (int i = 0; i < left.size(); i++) {
                if (same(left.get(i), each)) {
                    left.remove(i);
                    break;
                }
            }
        }
        return left;
    }

    // The elements of one list that another does not hold at all, each once.
    public static List<Object> missing(List<?> elements, List<?> from) {
        List<Object> absent = new ArrayList<>();
        for (Object each : elements) {
            if (!holds(from, each) && !holds(absent, each)) {
                absent.add(each);
            }
        }
        return absent;
    }

    // A throwable as a failed check shows it: its text and the causes under it.
    public static String thrown(Throwable thrown) {
        StringBuilder text = new StringBuilder(thrown.toString());
        Throwable cause = thrown.getCause();
        int depth = 0;
        while (cause != null && depth++ < 16) {
            text.append("\nCaused by: ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }
}

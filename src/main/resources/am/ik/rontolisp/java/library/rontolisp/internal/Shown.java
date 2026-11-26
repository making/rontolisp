// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// How a failed assertion shows a value, and the comparisons of elements its checks are
// made of: a text between quotes, a long and a float with their letters, the elements
// of an array, a collection and a map each shown the same way.
public final class Shown {

    private Shown() {
    }

    public static String of(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
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
            return type.getName();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder text = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                text.append(first ? "" : ", ").append(of(entry.getKey())).append('=').append(of(entry.getValue()));
                first = false;
            }
            return text.append('}').toString();
        }
        if (value instanceof Map.Entry<?, ?> entry) {
            return of(entry.getKey()) + "=" + of(entry.getValue());
        }
        if (value instanceof Iterable<?> elements) {
            return ofAll(listOf(elements));
        }
        List<Object> elements = elementsOf(value);
        return elements != null ? ofAll(elements) : String.valueOf(value);
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

    // A value on the lines under a heading: each of its lines two spaces in.
    public static String under(Object value) {
        return "  " + of(value).replace("\n", "\n  ");
    }

    // A value after a heading on its line, or under it where it has several lines.
    public static String after(Object value) {
        String text = of(value);
        return text.indexOf('\n') < 0 ? text : "\n  " + text.replace("\n", "\n  ");
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

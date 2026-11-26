// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;

// A recursive comparison of two values, field by field, as AssertJ 3.27's answers to
// probes under a JDK make it: a JDK's types by equals (a double, a float within a
// precision), an optional, an array, a collection and a map by what they hold (a list,
// a sorted set and a linked set in order, any other collection in any order), an enum
// by equals, any other object by its properties or fields, the expected value's kind
// deciding. Each difference is a path and what a failure shows of it, sorted by path.
public final class Recursion {

    public final List<String> ignored = new ArrayList<>();

    public final List<String> compared = new ArrayList<>();

    public boolean ignoringActualNulls;

    public boolean ignoringExpectedNulls;

    public boolean ignoringOrder;

    public boolean strict;

    private final List<String[]> found = new ArrayList<>();

    private final List<Object[]> visited = new ArrayList<>();

    // The configuration as a failure describes it, a line each.
    public String configuration() {
        StringBuilder text = new StringBuilder("The recursive comparison was performed with this configuration:\n");
        if (ignoringActualNulls) {
            text.append("- all actual null fields were ignored in the comparison\n");
        }
        if (ignoringExpectedNulls) {
            text.append("- all expected null fields were ignored in the comparison\n");
        }
        if (!compared.isEmpty()) {
            text.append("- the comparison was performed on the following fields: ").append(String.join(", ", compared))
                .append('\n');
        }
        if (!ignored.isEmpty()) {
            text.append("- the following fields were ignored in the comparison: ").append(String.join(", ", ignored))
                .append('\n');
        }
        text.append("- no equals methods were used in the comparison EXCEPT for java JDK types since introspecting JDK"
                + " types is forbidden in java 17+ (use withEqualsForType to register a specific way to compare a JDK"
                + " type if you need it)\n");
        if (ignoringOrder) {
            text.append("- collection order was ignored in all fields in the comparison\n");
        }
        text.append("- these types were compared with the following comparators:\n"
                + "  - java.lang.Double -> DoubleComparator[precision=1.0E-15]\n"
                + "  - java.lang.Float -> FloatComparator[precision=1.0E-6]\n"
                + "  - java.nio.file.Path -> lexicographic comparator (Path natural order)\n");
        if (strict) {
            text.append("- actual and expected objects and their fields were considered different when of incompatible"
                    + " types (i.e. expected type does not extend actual's type) even if all their fields match, for"
                    + " example a Person instance will never match a PersonDto (call strictTypeChecking(false) to"
                    + " change that behavior).\n");
        }
        else {
            text.append("- actual and expected objects and their fields were compared field by field recursively even"
                    + " if they were not of the same type, this allows for example to compare a Person to a PersonDto"
                    + " (call strictTypeChecking(true) to change that behavior).\n");
        }
        return text.append("- the introspection strategy used was: DefaultRecursiveComparisonIntrospectionStrategy\n")
            .toString();
    }

    // The differences between two values, sorted by their paths, each as a failure shows it.
    public List<String> differences(Object actual, Object expected) {
        found.clear();
        visited.clear();
        compare("", "", actual, expected);
        List<String[]> sorted = new ArrayList<>(found);
        sorted.sort((one, other) -> one[0].compareTo(other[0]));
        List<String> texts = new ArrayList<>();
        for (String[] each : sorted) {
            texts.add(each[1]);
        }
        return texts;
    }

    // Whether two values hold no difference, compared as this comparison compares them.
    private boolean alike(String rule, Object actual, Object expected) {
        Recursion nested = new Recursion();
        nested.ignored.addAll(ignored);
        nested.compared.addAll(compared);
        nested.ignoringActualNulls = ignoringActualNulls;
        nested.ignoringExpectedNulls = ignoringExpectedNulls;
        nested.ignoringOrder = ignoringOrder;
        nested.strict = strict;
        nested.compare(rule, rule, actual, expected);
        return nested.found.isEmpty();
    }

    private static String joined(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    // Whether a field at a path is left out of the comparison.
    private boolean skipped(String rule) {
        if (ignored.contains(rule)) {
            return true;
        }
        if (compared.isEmpty()) {
            return false;
        }
        for (String each : compared) {
            if (rule.equals(each) || each.startsWith(rule + ".") || rule.startsWith(each + ".")) {
                return false;
            }
        }
        return true;
    }

    private void differ(String path, Object actual, Object expected, String why) {
        String actualText = Shown.of(actual);
        String expectedText = Shown.of(expected);
        if (actualText.equals(expectedText)) {
            actualText += identified(actual);
            expectedText += identified(expected);
        }
        String heading;
        if (path.isEmpty()) {
            heading = "Top level actual and expected objects differ:";
        }
        else if (path.startsWith("[") && path.indexOf('[', 1) < 0 && path.indexOf('.') < 0) {
            heading = "Top level actual and expected objects element at index " + path.substring(1, path.length() - 1)
                    + " differ:";
        }
        else {
            heading = "field/property '" + path + "' differ:";
        }
        found.add(new String[] { path, heading + "\n- actual value  : " + actualText + "\n- expected value: " + expectedText
                + (why == null ? "" : "\n" + why) });
    }

    private static String identified(Object value) {
        return value == null ? ""
                : " (" + value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)) + ")";
    }

    private static String named(Object value) {
        String name = value.getClass().getCanonicalName();
        return name == null ? value.getClass().getName() : name;
    }

    private static boolean isJava(Object value) {
        return value.getClass().getName().startsWith("java.");
    }

    private static boolean isOrdered(Object value) {
        return value instanceof List || value instanceof SortedSet || value instanceof java.util.LinkedHashSet;
    }

    private boolean seen(Object actual, Object expected) {
        for (Object[] pair : visited) {
            if (pair[0] == actual && pair[1] == expected) {
                return true;
            }
        }
        visited.add(new Object[] { actual, expected });
        return false;
    }

    private void compare(String path, String rule, Object actual, Object expected) {
        if (!path.isEmpty()) {
            if (skipped(rule) || (ignoringActualNulls && actual == null) || (ignoringExpectedNulls && expected == null)) {
                return;
            }
        }
        if (actual == expected) {
            return;
        }
        if (actual == null || expected == null) {
            differ(path, actual, expected, null);
            return;
        }
        if (actual instanceof Double one) {
            if (!(expected instanceof Double other)) {
                unsuited(path, actual, expected, "DoubleComparator[precision=1.0E-15]");
            }
            else if (!(one.doubleValue() == other.doubleValue() || Math.abs(one - other) <= 1.0E-15)) {
                differ(path, actual, expected, null);
            }
            return;
        }
        if (actual instanceof Float one) {
            if (!(expected instanceof Float other)) {
                unsuited(path, actual, expected, "FloatComparator[precision=1.0E-6]");
            }
            else if (!(one.floatValue() == other.floatValue() || Math.abs(one - other) <= 1.0E-6f)) {
                differ(path, actual, expected, null);
            }
            return;
        }
        if (expected instanceof Optional<?> other) {
            if (!(actual instanceof Optional<?> one)) {
                differ(path, actual, expected, "expected field is an Optional but actual field is not (" + named(actual) + ")");
            }
            else if (one.isPresent() && other.isPresent()) {
                compare(joined(path, "value"), joined(rule, "value"), one.get(), other.get());
            }
            else if (one.isPresent() != other.isPresent()) {
                differ(path, actual, expected, null);
            }
            return;
        }
        List<Object> expectedElements = Shown.elementsOf(expected);
        if (expectedElements != null) {
            List<Object> actualElements = Shown.elementsOf(actual);
            if (actualElements == null) {
                differ(path, actual, expected, "expected field is an array but actual field is not (" + named(actual) + ")");
            }
            else if (actualElements.size() != expectedElements.size()) {
                differ(path, actual, expected, "actual and expected values are arrays of different size, actual size="
                        + actualElements.size() + " when expected size=" + expectedElements.size());
            }
            else if (!seen(actual, expected)) {
                for (int i = 0; i < actualElements.size(); i++) {
                    compare(path + "[" + i + "]", rule, actualElements.get(i), expectedElements.get(i));
                }
            }
            return;
        }
        if (expected instanceof Iterable<?> other) {
            if (isOrdered(expected) && !ignoringOrder) {
                if (!isOrdered(actual)) {
                    differ(path, actual, expected, "expected field is an ordered collection but actual field is not ("
                            + named(actual) + "), ordered collections are: [java.util.List, java.util.SortedSet,"
                            + " java.util.LinkedHashSet]");
                    return;
                }
                List<Object> actualElements = Shown.listOf((Iterable<?>) actual);
                List<Object> otherElements = Shown.listOf(other);
                if (sized(path, actual, expected, actualElements.size(), otherElements.size()) && !seen(actual, expected)) {
                    for (int i = 0; i < actualElements.size(); i++) {
                        compare(path + "[" + i + "]", rule, actualElements.get(i), otherElements.get(i));
                    }
                }
                return;
            }
            if (!(actual instanceof Iterable<?> one)) {
                differ(path, actual, expected, "expected field is an iterable but actual field is not (" + named(actual) + ")");
                return;
            }
            List<Object> left = Shown.listOf(one);
            List<Object> otherElements = Shown.listOf(other);
            if (!sized(path, actual, expected, left.size(), otherElements.size()) || seen(actual, expected)) {
                return;
            }
            List<Object> unmatched = new ArrayList<>();
            for (Object each : otherElements) {
                Iterator<Object> candidates = left.iterator();
                boolean matched = false;
                while (!matched && candidates.hasNext()) {
                    if (alike(rule, candidates.next(), each)) {
                        candidates.remove();
                        matched = true;
                    }
                }
                if (!matched) {
                    unmatched.add(each);
                }
            }
            if (!unmatched.isEmpty()) {
                differ(path, actual, expected, "The following expected elements were not matched in the actual "
                        + actual.getClass().getSimpleName() + ":\n  " + Shown.ofAll(unmatched));
            }
            return;
        }
        if (expected instanceof Map<?, ?> other) {
            if (!(actual instanceof Map<?, ?> one)) {
                differ(path, actual, expected, "expected field is a map but actual field is not (" + named(actual) + ")");
                return;
            }
            if (one.size() != other.size()) {
                differ(path, actual, expected, "actual and expected values are maps of different size, actual size="
                        + one.size() + " when expected size=" + other.size());
                return;
            }
            if (seen(actual, expected)) {
                return;
            }
            if (expected instanceof SortedMap<?, ?> && actual instanceof SortedMap<?, ?>) {
                Iterator<?> keys = one.keySet().iterator();
                for (Object key : other.keySet()) {
                    Object actualKey = keys.next();
                    if (!Shown.same(actualKey, key)) {
                        differ(path, actual, expected, "map key difference:\n- actual key  : " + Shown.of(actualKey)
                                + "\n- expected key: " + Shown.of(key));
                        return;
                    }
                }
            }
            else {
                List<Object> absent = new ArrayList<>();
                for (Object key : other.keySet()) {
                    if (!one.containsKey(key)) {
                        absent.add(key);
                    }
                }
                if (!absent.isEmpty()) {
                    differ(path, actual, expected, "The following keys were not found in the actual map value:\n  "
                            + Shown.ofAll(absent));
                    return;
                }
            }
            for (Map.Entry<?, ?> entry : other.entrySet()) {
                String name = String.valueOf(entry.getKey());
                compare(joined(path, name), joined(rule, name), one.get(entry.getKey()), entry.getValue());
            }
            return;
        }
        if (isJava(actual) || isJava(expected)) {
            if (!actual.equals(expected)) {
                differ(path, actual, expected, "Compared objects have java types and were thus compared with equals method");
            }
            return;
        }
        if (actual instanceof Enum<?> || expected instanceof Enum<?>) {
            if (!actual.equals(expected)) {
                differ(path, actual, expected, null);
            }
            return;
        }
        if (strict && !actual.getClass().isInstance(expected)) {
            differ(path, actual, expected, "the fields are considered different since the comparison enforces strict type"
                    + " check and " + expected.getClass().getName() + " is not a subtype of " + actual.getClass().getName());
            return;
        }
        if (seen(actual, expected)) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (String name : Introspected.fieldNames(actual.getClass())) {
            if (!skipped(joined(rule, name))) {
                names.add(name);
            }
        }
        List<String> lacking = new ArrayList<>(names);
        lacking.removeAll(Introspected.fieldNames(expected.getClass()));
        lacking.sort(null);
        if (!lacking.isEmpty()) {
            String expectedName = expected.getClass().getSimpleName();
            differ(path, actual, expected, actual.getClass().getName() + " can't be compared to " + expected.getClass().getName()
                    + " as " + expectedName + " does not declare all " + actual.getClass().getSimpleName()
                    + " fields, it lacks these: " + lacking);
            return;
        }
        for (String name : names) {
            compare(joined(path, name), joined(rule, name), Introspected.value(actual, name),
                    Introspected.value(expected, name));
        }
    }

    private boolean sized(String path, Object actual, Object expected, int actualSize, int expectedSize) {
        if (actualSize != expectedSize) {
            differ(path, actual, expected, "actual and expected values are collections of different size, actual size="
                    + actualSize + " when expected size=" + expectedSize);
            return false;
        }
        return true;
    }

    private void unsuited(String path, Object actual, Object expected, String comparator) {
        System.out.println("WARNING: Comparator was not suited to compare '" + path + "' field values:\n- actual field value  : "
                + actual + "\n- expected field value: " + expected + "\n- comparator used     : " + comparator);
        differ(path, actual, expected, null);
    }
}

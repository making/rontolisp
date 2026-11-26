// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.assertj.core.data.MapEntry;
import rontolisp.internal.Shown;

// The checks of a map: its size, its keys, its values and its entries.
public class MapAssert<KEY, VALUE> extends AbstractAssert<MapAssert<KEY, VALUE>, Map<KEY, VALUE>> {

    public MapAssert(Map<KEY, VALUE> actual) {
        super(actual);
    }

    private String expecting(String relation, Object other) {
        return "\nExpecting actual:\n" + Shown.under(actual) + "\n" + relation + ":\n" + Shown.under(other);
    }

    public void isEmpty() {
        isNotNull();
        if (!actual.isEmpty()) {
            throw failure("\nExpecting empty but was: " + Shown.of(actual));
        }
    }

    public void isNullOrEmpty() {
        if (actual != null && !actual.isEmpty()) {
            throw failure("\nExpecting null or empty but was: " + Shown.of(actual));
        }
    }

    public MapAssert<KEY, VALUE> isNotEmpty() {
        isNotNull();
        if (actual.isEmpty()) {
            throw failure("\nExpecting actual not to be empty");
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> hasSize(int expected) {
        isNotNull();
        if (actual.size() != expected) {
            throw failure("\nExpected size: " + expected + " but was: " + actual.size() + " in:\n" + Shown.of(actual));
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> containsKey(KEY key) {
        isNotNull();
        if (!actual.containsKey(key)) {
            throw failure(expecting("to contain key", key));
        }
        return myself;
    }

    @SafeVarargs
    public final MapAssert<KEY, VALUE> containsKeys(KEY... keys) {
        isNotNull();
        List<Object> absent = new ArrayList<>();
        for (KEY key : keys) {
            if (!actual.containsKey(key)) {
                absent.add(key);
            }
        }
        if (absent.size() == 1) {
            throw failure(expecting("to contain key", absent.get(0)));
        }
        if (!absent.isEmpty()) {
            throw failure(expecting("to contain keys", absent));
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> doesNotContainKey(KEY key) {
        isNotNull();
        if (actual.containsKey(key)) {
            throw failure(expecting("not to contain key", key));
        }
        return myself;
    }

    @SafeVarargs
    public final MapAssert<KEY, VALUE> doesNotContainKeys(KEY... keys) {
        for (KEY key : keys) {
            doesNotContainKey(key);
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> containsValue(VALUE value) {
        isNotNull();
        if (!actual.containsValue(value)) {
            throw failure(expecting("to contain value", value));
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> doesNotContainValue(VALUE value) {
        isNotNull();
        if (actual.containsValue(value)) {
            throw failure(expecting("not to contain value", value));
        }
        return myself;
    }

    public MapAssert<KEY, VALUE> containsEntry(KEY key, VALUE value) {
        return entries(List.of(MapEntry.entry(key, value)));
    }

    @SafeVarargs
    public final MapAssert<KEY, VALUE> contains(Map.Entry<? extends KEY, ? extends VALUE>... entries) {
        return entries(List.of(entries));
    }

    public MapAssert<KEY, VALUE> containsAllEntriesOf(Map<? extends KEY, ? extends VALUE> other) {
        return entries(new ArrayList<>(other.entrySet()));
    }

    private MapAssert<KEY, VALUE> entries(List<? extends Map.Entry<?, ?>> expected) {
        isNotNull();
        List<Object> absent = new ArrayList<>();
        List<String> different = new ArrayList<>();
        for (Map.Entry<?, ?> entry : expected) {
            if (!actual.containsKey(entry.getKey())) {
                absent.add(entry);
            }
            else if (!Shown.same(actual.get(entry.getKey()), entry.getValue())) {
                different.add(Shown.of(entry.getKey()) + "=" + Shown.of(actual.get(entry.getKey())) + " (expected: "
                        + Shown.of(entry.getValue()) + ")");
            }
        }
        if (absent.isEmpty() && different.isEmpty()) {
            return myself;
        }
        StringBuilder text = new StringBuilder("\nExpecting map:\n").append(Shown.under(actual))
            .append("\nto contain entries:\n")
            .append(Shown.under(expected));
        if (!absent.isEmpty()) {
            text.append("\nbut could not find the following map entries:\n").append(Shown.under(absent));
        }
        if (!different.isEmpty()) {
            text.append(absent.isEmpty() ? "\nbut" : "\nand").append(" the following map entries had different values:\n  [")
                .append(String.join(", ", different))
                .append("]");
        }
        throw failure(text.toString());
    }

    public MapAssert<KEY, VALUE> doesNotContainEntry(KEY key, VALUE value) {
        isNotNull();
        if (actual.containsKey(key) && Shown.same(actual.get(key), value)) {
            List<Object> entry = List.of(MapEntry.entry(key, value));
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nnot to contain\n" + Shown.under(entry) + "\nbut found\n"
                    + Shown.under(entry) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final MapAssert<KEY, VALUE> containsOnlyKeys(KEY... keys) {
        isNotNull();
        List<Object> expected = List.of(keys);
        List<Object> held = new ArrayList<>(actual.keySet());
        String heading = "\nExpecting actual:\n" + Shown.under(actual) + "\nto contain only following keys:\n"
                + Shown.under(expected) + "\n";
        List<Object> absent = Shown.missing(expected, held);
        List<Object> unexpected = Shown.missing(held, expected);
        if (!absent.isEmpty() && !unexpected.isEmpty()) {
            throw failure(heading + "keys not found:\n" + Shown.under(absent) + "\nand keys not expected:\n"
                    + Shown.under(unexpected) + "\n");
        }
        if (!absent.isEmpty()) {
            throw failure(heading + "but could not find the following keys:\n" + Shown.under(absent) + "\n");
        }
        if (!unexpected.isEmpty()) {
            throw failure(heading + "but the following keys were unexpected:\n" + Shown.under(unexpected) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final MapAssert<KEY, VALUE> containsOnly(Map.Entry<? extends KEY, ? extends VALUE>... entries) {
        isNotNull();
        List<Object> expected = new ArrayList<>();
        for (Map.Entry<? extends KEY, ? extends VALUE> entry : entries) {
            expected.add(MapEntry.entry(entry.getKey(), entry.getValue()));
        }
        List<Object> held = new ArrayList<>();
        for (Map.Entry<KEY, VALUE> entry : actual.entrySet()) {
            held.add(MapEntry.entry(entry.getKey(), entry.getValue()));
        }
        String heading = "\nExpecting map:\n" + Shown.under(actual) + "\nto contain only:\n" + Shown.under(expected) + "\n";
        List<Object> absent = Shown.missing(expected, held);
        List<Object> unexpected = Shown.missing(held, expected);
        if (!absent.isEmpty() && !unexpected.isEmpty()) {
            throw failure(heading + "map entries not found:\n" + Shown.under(absent) + "\nand map entries not expected:\n"
                    + Shown.under(unexpected) + "\n");
        }
        if (!absent.isEmpty()) {
            throw failure(heading + "but could not find the following map entries:\n" + Shown.under(absent) + "\n");
        }
        if (!unexpected.isEmpty()) {
            throw failure(heading + "but the following map entries were unexpected:\n" + Shown.under(unexpected) + "\n");
        }
        return myself;
    }
}

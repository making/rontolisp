// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.assertj.core.groups.Tuple;
import rontolisp.internal.Introspected;
import rontolisp.internal.Shown;

// The checks of the elements of a collection, in the order it gives them.
public abstract class AbstractIterableAssert<SELF extends AbstractIterableAssert<SELF, ACTUAL, ELEMENT>, ACTUAL extends Iterable<? extends ELEMENT>, ELEMENT>
        extends AbstractAssert<SELF, ACTUAL> {

    protected AbstractIterableAssert(ACTUAL actual) {
        super(actual);
    }

    // The elements, of a collection there is.
    protected List<Object> elements() {
        isNotNull();
        return Shown.listOf(actual);
    }

    // What the collection is called where a failure names its class.
    protected String kind() {
        return actual.getClass().getSimpleName();
    }

    public void isNullOrEmpty() {
        if (actual != null && actual.iterator().hasNext()) {
            throw failure("\nExpecting null or empty but was: " + Shown.of(actual));
        }
    }

    public void isEmpty() {
        if (!elements().isEmpty()) {
            throw failure("\nExpecting empty but was: " + Shown.of(actual));
        }
    }

    public SELF isNotEmpty() {
        if (elements().isEmpty()) {
            throw failure("\nExpecting actual not to be empty");
        }
        return myself;
    }

    public SELF hasSize(int expected) {
        int size = elements().size();
        if (size != expected) {
            throw failure("\nExpected size: " + expected + " but was: " + size + " in:\n" + Shown.of(actual));
        }
        return myself;
    }

    private SELF sized(boolean holds, String relation, int boundary, int size) {
        if (!holds) {
            throw failure("\nExpecting size of:\n" + Shown.under(actual) + "\nto be " + relation + " " + boundary + " but was "
                    + size);
        }
        return myself;
    }

    public SELF hasSizeLessThan(int boundary) {
        int size = elements().size();
        return sized(size < boundary, "less than", boundary, size);
    }

    public SELF hasSizeLessThanOrEqualTo(int boundary) {
        int size = elements().size();
        return sized(size <= boundary, "less than or equal to", boundary, size);
    }

    public SELF hasSizeGreaterThan(int boundary) {
        int size = elements().size();
        return sized(size > boundary, "greater than", boundary, size);
    }

    public SELF hasSizeGreaterThanOrEqualTo(int boundary) {
        int size = elements().size();
        return sized(size >= boundary, "greater than or equal to", boundary, size);
    }

    // ---- which elements

    @SafeVarargs
    public final SELF contains(ELEMENT... values) {
        return containsAll(List.of(values), "element(s)");
    }

    public SELF containsAll(Iterable<? extends ELEMENT> iterable) {
        return containsAll(Shown.listOf(iterable), "element(s)");
    }

    protected SELF containsAll(List<?> expected, String things) {
        List<Object> absent = Shown.missing(expected, elements());
        if (!absent.isEmpty()) {
            throw failure("\nExpecting " + kind() + ":\n" + Shown.under(actual) + "\nto contain:\n" + Shown.under(expected)
                    + "\nbut could not find the following " + things + ":\n" + Shown.under(absent) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final SELF doesNotContain(ELEMENT... values) {
        List<Object> present = new ArrayList<>();
        List<Object> held = elements();
        for (Object value : values) {
            if (Shown.holds(held, value) && !Shown.holds(present, value)) {
                present.add(value);
            }
        }
        if (!present.isEmpty()) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nnot to contain\n" + Shown.under(values) + "\nbut found\n"
                    + Shown.under(present) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final SELF containsExactly(ELEMENT... values) {
        return exactly(List.of(values));
    }

    public SELF containsExactlyElementsOf(Iterable<? extends ELEMENT> iterable) {
        return exactly(Shown.listOf(iterable));
    }

    protected SELF exactly(List<?> expected) {
        List<Object> held = elements();
        String heading = "\nExpecting actual:\n" + Shown.under(actual) + "\nto contain exactly (and in same order):\n"
                + Shown.under(expected) + "\n";
        List<Object> absent = Shown.without(expected, held);
        List<Object> unexpected = Shown.without(held, expected);
        if (!absent.isEmpty() && !unexpected.isEmpty()) {
            throw unequal(heading + "but some elements were not found:\n" + Shown.under(absent)
                    + "\nand others were not expected:\n" + Shown.under(unexpected) + "\n", expected, actual);
        }
        if (!absent.isEmpty()) {
            throw unequal(heading + "but could not find the following elements:\n" + Shown.under(absent) + "\n", expected,
                    actual);
        }
        if (!unexpected.isEmpty()) {
            throw unequal(heading + "but some elements were not expected:\n" + Shown.under(unexpected) + "\n", expected,
                    actual);
        }
        StringBuilder differences = new StringBuilder();
        for (int i = 0; i < held.size(); i++) {
            if (!Shown.same(held.get(i), expected.get(i))) {
                differences.append("  - element at index ")
                    .append(i)
                    .append(": expected ")
                    .append(Shown.of(expected.get(i)))
                    .append(" but was ")
                    .append(Shown.of(held.get(i)))
                    .append("\n");
            }
        }
        if (differences.length() > 0) {
            throw unequal(heading + "but there were differences at these indexes:\n" + differences, expected, actual);
        }
        return myself;
    }

    @SafeVarargs
    public final SELF containsExactlyInAnyOrder(ELEMENT... values) {
        return inAnyOrder(List.of(values));
    }

    public SELF containsExactlyInAnyOrderElementsOf(Iterable<? extends ELEMENT> iterable) {
        return inAnyOrder(Shown.listOf(iterable));
    }

    protected SELF inAnyOrder(List<?> expected) {
        List<Object> held = elements();
        String heading = "\nExpecting actual:\n" + Shown.under(actual) + "\nto contain exactly in any order:\n"
                + Shown.under(expected) + "\n";
        List<Object> absent = Shown.without(expected, held);
        List<Object> unexpected = Shown.without(held, expected);
        if (!absent.isEmpty() && !unexpected.isEmpty()) {
            throw failure(heading + "elements not found:\n" + Shown.under(absent) + "\nand elements not expected:\n"
                    + Shown.under(unexpected) + "\n");
        }
        if (!absent.isEmpty()) {
            throw failure(heading + "but could not find the following elements:\n" + Shown.under(absent) + "\n");
        }
        if (!unexpected.isEmpty()) {
            throw failure(heading + "but the following elements were unexpected:\n" + Shown.under(unexpected) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final SELF containsOnly(ELEMENT... values) {
        return only(List.of(values), "element(s)");
    }

    protected SELF only(List<?> expected, String things) {
        List<Object> held = elements();
        String heading = "\nExpecting ArrayList:\n" + Shown.under(actual) + "\nto contain only:\n"
                + Shown.under(expected) + "\n";
        List<Object> absent = Shown.missing(expected, held);
        List<Object> unexpected = Shown.missing(held, expected);
        if (!absent.isEmpty() && !unexpected.isEmpty()) {
            throw failure(heading + things + " not found:\n" + Shown.under(absent) + "\nand " + things + " not expected:\n"
                    + Shown.under(unexpected) + "\n");
        }
        if (!absent.isEmpty()) {
            throw failure(heading + "but could not find the following " + things + ":\n" + Shown.under(absent) + "\n");
        }
        if (!unexpected.isEmpty()) {
            throw failure(heading + "but the following " + things + " were unexpected:\n" + Shown.under(unexpected) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final SELF containsSequence(ELEMENT... sequence) {
        List<Object> held = elements();
        for (int start = 0; start + sequence.length <= held.size(); start++) {
            boolean found = true;
            for (int i = 0; found && i < sequence.length; i++) {
                found = Shown.same(held.get(start + i), sequence[i]);
            }
            if (found) {
                return myself;
            }
        }
        throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto contain sequence:\n" + Shown.under(sequence) + "\n");
    }

    @SafeVarargs
    public final SELF containsSubsequence(ELEMENT... subsequence) {
        List<Object> held = elements();
        int at = 0;
        for (int i = 0; i < subsequence.length; i++) {
            while (at < held.size() && !Shown.same(held.get(at), subsequence[i])) {
                at++;
            }
            if (at == held.size()) {
                throw failure("\nExpecting actual to contain the specified subsequence but failed to find the element at"
                        + " subsequence index " + i + " in actual:\nsubsequence element not found in actual:\n"
                        + Shown.under(subsequence[i]) + "\nactual:\n" + Shown.under(actual) + "\nsubsequence:\n"
                        + Shown.under(subsequence));
            }
            at++;
        }
        return myself;
    }

    // ---- what the elements are like

    @SuppressWarnings("unchecked")
    public SELF anyMatch(Predicate<? super ELEMENT> predicate) {
        for (Object element : elements()) {
            if (predicate.test((ELEMENT) element)) {
                return myself;
            }
        }
        throw failure("\nExpecting any elements of:\n" + Shown.under(actual) + "\nto match given predicate but none did.");
    }

    @SuppressWarnings("unchecked")
    public SELF allMatch(Predicate<? super ELEMENT> predicate) {
        List<Object> others = new ArrayList<>();
        for (Object element : elements()) {
            if (!predicate.test((ELEMENT) element)) {
                others.add(element);
            }
        }
        if (!others.isEmpty()) {
            throw failure("\nExpecting all elements of:\n" + Shown.under(actual) + "\nto match given predicate but "
                    + (others.size() == 1 ? "this element did not:\n" + Shown.under(others.get(0))
                            : "these elements did not:\n" + Shown.under(others)));
        }
        return myself;
    }

    @SuppressWarnings("unchecked")
    public SELF noneMatch(Predicate<? super ELEMENT> predicate) {
        for (Object element : elements()) {
            if (predicate.test((ELEMENT) element)) {
                throw failure("\nExpecting no elements of:\n" + Shown.under(actual)
                        + "\nto match given predicate but this element did:\n" + Shown.under(element));
            }
        }
        return myself;
    }

    @SuppressWarnings("unchecked")
    public SELF allSatisfy(Consumer<? super ELEMENT> requirements) {
        StringBuilder failed = new StringBuilder();
        for (Object element : elements()) {
            try {
                requirements.accept((ELEMENT) element);
            }
            catch (AssertionError error) {
                failed.append("\n\n").append(Shown.of(element)).append("\nerror: ").append(error);
            }
        }
        if (failed.length() > 0) {
            throw failure("\nExpecting all elements of:\n" + Shown.under(actual)
                    + "\nto satisfy given requirements, but these elements did not:" + failed);
        }
        return myself;
    }

    @SuppressWarnings("unchecked")
    public SELF anySatisfy(Consumer<? super ELEMENT> requirements) {
        StringBuilder failed = new StringBuilder();
        for (Object element : elements()) {
            try {
                requirements.accept((ELEMENT) element);
                return myself;
            }
            catch (AssertionError error) {
                failed.append("\n\n").append(Shown.of(element)).append("\nerror: ").append(error);
            }
        }
        throw failure("\nExpecting any element of:\n" + Shown.under(actual)
                + "\nto satisfy the given assertions requirements but none did:" + failed);
    }

    // ---- the elements, asserted on in their turn

    @SuppressWarnings("unchecked")
    public <V> ListAssert<V> extracting(Function<? super ELEMENT, V> extractor) {
        List<V> extracted = new ArrayList<>();
        for (Object element : elements()) {
            extracted.add(extractor.apply((ELEMENT) element));
        }
        return new ListAssert<>(extracted);
    }

    public ListAssert<Object> extracting(String propertyOrField) {
        List<Object> extracted = new ArrayList<>();
        for (Object element : elements()) {
            extracted.add(Introspected.value(element, propertyOrField));
        }
        return extracted(new ListAssert<>(extracted), propertyOrField);
    }

    public ListAssert<Tuple> extracting(String... propertiesOrFields) {
        List<Tuple> extracted = new ArrayList<>();
        for (Object element : elements()) {
            Object[] values = new Object[propertiesOrFields.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = Introspected.value(element, propertiesOrFields[i]);
            }
            extracted.add(new Tuple(values));
        }
        return extracted(new ListAssert<>(extracted), String.join(", ", propertiesOrFields));
    }

    public <V> ListAssert<V> map(Function<? super ELEMENT, V> mapper) {
        return extracting(mapper);
    }

    @SuppressWarnings("unchecked")
    public ListAssert<ELEMENT> filteredOn(Predicate<? super ELEMENT> predicate) {
        List<ELEMENT> kept = new ArrayList<>();
        for (Object element : elements()) {
            if (predicate.test((ELEMENT) element)) {
                kept.add((ELEMENT) element);
            }
        }
        return new ListAssert<>(kept);
    }

    public ListAssert<ELEMENT> filteredOn(String propertyOrFieldName, Object expectedValue) {
        return filteredOn(element -> Shown.same(Introspected.value(element, propertyOrFieldName), expectedValue));
    }

    @Override
    public RecursiveComparisonAssert<?> usingRecursiveComparison() {
        return super.usingRecursiveComparison();
    }

    @SuppressWarnings("unchecked")
    private ObjectAssert<ELEMENT> element(int index, String described) {
        List<Object> held = elements();
        if (held.isEmpty()) {
            throw failure("\nExpecting actual not to be empty");
        }
        if (index < 0 || index >= held.size()) {
            throw failure("Index " + index + " out of bounds for length " + held.size());
        }
        return new ObjectAssert<>((ELEMENT) held.get(index)).as(described);
    }

    public ObjectAssert<ELEMENT> first() {
        return element(0, navigated() + " check first element");
    }

    public ObjectAssert<ELEMENT> last() {
        int size = elements().size();
        return element(size == 0 ? 0 : size - 1, navigated() + " check last element");
    }

    public ObjectAssert<ELEMENT> element(int index) {
        return element(index, navigated() + " element at index " + index);
    }

    public ObjectAssert<ELEMENT> singleElement() {
        hasSize(1);
        return element(0, navigated() + " check single element");
    }

    // What an element taken from the collection is described as coming from.
    protected String navigated() {
        return "Iterable";
    }
}

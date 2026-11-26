// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Shown;

// The checks of the elements of an array of long: those of a list of them.
public abstract class AbstractLongArrayAssert<SELF extends AbstractLongArrayAssert<SELF>> extends AbstractAssert<SELF, long[]> {

    protected AbstractLongArrayAssert(long[] actual) {
        super(actual);
    }

    private ListAssert<Long> listed() {
        isNotNull();
        List<Long> elements = new ArrayList<>();
        for (long element : actual) {
            elements.add(element);
        }
        ListAssert<Long> listed = new ListAssert<>(elements);
        if (descriptionText() != null) {
            listed.as(descriptionText());
        }
        return listed;
    }

    private static Long[] boxed(long[] values) {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }

    public void isEmpty() {
        listed().isEmpty();
    }

    public void isNullOrEmpty() {
        if (actual != null && actual.length > 0) {
            throw failure("\nExpecting null or empty but was: " + Shown.of(actual));
        }
    }

    public SELF isNotEmpty() {
        listed().isNotEmpty();
        return myself;
    }

    public SELF hasSize(int expected) {
        listed().hasSize(expected);
        return myself;
    }

    public SELF contains(long... values) {
        isNotNull();
        List<Object> absent = Shown.missing(List.of(boxed(values)), Shown.elementsOf(actual));
        if (!absent.isEmpty()) {
            throw failure("\nExpecting long[]:\n" + Shown.under(actual) + "\nto contain:\n" + Shown.under(values)
                    + "\nbut could not find the following long(s):\n" + Shown.under(absent) + "\n");
        }
        return myself;
    }

    public SELF doesNotContain(long... values) {
        listed().doesNotContain(boxed(values));
        return myself;
    }

    public SELF containsExactly(long... values) {
        listed().containsExactly(boxed(values));
        return myself;
    }

    public SELF containsExactlyInAnyOrder(long... values) {
        listed().containsExactlyInAnyOrder(boxed(values));
        return myself;
    }

    public SELF startsWith(long... sequence) {
        isNotNull();
        boolean starts = sequence.length <= actual.length;
        for (int i = 0; starts && i < sequence.length; i++) {
            starts = actual[i] == sequence[i];
        }
        if (!starts) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto start with:\n" + Shown.under(sequence) + "\n");
        }
        return myself;
    }
}

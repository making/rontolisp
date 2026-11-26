// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Shown;

// The checks of an array's elements: those of a list of them.
public class ObjectArrayAssert<ELEMENT> extends AbstractAssert<ObjectArrayAssert<ELEMENT>, ELEMENT[]> {

    public ObjectArrayAssert(ELEMENT[] actual) {
        super(actual);
    }

    private ListAssert<ELEMENT> listed() {
        isNotNull();
        List<ELEMENT> elements = new ArrayList<>();
        for (ELEMENT element : actual) {
            elements.add(element);
        }
        ListAssert<ELEMENT> listed = new ListAssert<>(elements);
        if (descriptionText() != null) {
            listed.as(descriptionText());
        }
        return listed;
    }

    public void isEmpty() {
        listed().isEmpty();
    }

    public void isNullOrEmpty() {
        if (actual != null && actual.length > 0) {
            throw failure("\nExpecting null or empty but was: " + Shown.of(actual));
        }
    }

    public ObjectArrayAssert<ELEMENT> isNotEmpty() {
        listed().isNotEmpty();
        return myself;
    }

    public ObjectArrayAssert<ELEMENT> hasSize(int expected) {
        listed().hasSize(expected);
        return myself;
    }

    @SafeVarargs
    public final ObjectArrayAssert<ELEMENT> contains(ELEMENT... values) {
        isNotNull();
        List<Object> absent = Shown.missing(List.of(values), Shown.elementsOf(actual));
        if (!absent.isEmpty()) {
            String kind = actual.getClass().getSimpleName();
            throw failure("\nExpecting " + kind + ":\n" + Shown.under(actual) + "\nto contain:\n" + Shown.under(values)
                    + "\nbut could not find the following " + kind.substring(0, kind.length() - 2).toLowerCase() + "(s):\n"
                    + Shown.under(absent) + "\n");
        }
        return myself;
    }

    @SafeVarargs
    public final ObjectArrayAssert<ELEMENT> doesNotContain(ELEMENT... values) {
        listed().doesNotContain(values);
        return myself;
    }

    @SafeVarargs
    public final ObjectArrayAssert<ELEMENT> containsExactly(ELEMENT... values) {
        listed().containsExactly(values);
        return myself;
    }

    @SafeVarargs
    public final ObjectArrayAssert<ELEMENT> containsExactlyInAnyOrder(ELEMENT... values) {
        listed().containsExactlyInAnyOrder(values);
        return myself;
    }

    @SafeVarargs
    public final ObjectArrayAssert<ELEMENT> containsOnly(ELEMENT... values) {
        isNotNull();
        listed().containsOnly(values);
        return myself;
    }
}

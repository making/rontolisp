// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.Optional;
import java.util.function.Consumer;

import rontolisp.internal.Shown;
import rontolisp.internal.Uncollected;

// The checks of an optional value.
public class OptionalAssert<VALUE> extends AbstractAssert<OptionalAssert<VALUE>, Optional<VALUE>> {

    public OptionalAssert(Optional<VALUE> actual) {
        super(actual);
    }

    public OptionalAssert<VALUE> isPresent() {
        isNotNull();
        if (actual.isEmpty()) {
            throw failure("\nExpecting Optional to contain a value but it was empty.");
        }
        return myself;
    }

    public OptionalAssert<VALUE> isNotEmpty() {
        return isPresent();
    }

    public OptionalAssert<VALUE> isEmpty() {
        isNotNull();
        if (actual.isPresent()) {
            throw failure("\nExpecting an empty Optional but was containing value: " + Shown.of(actual.get()));
        }
        return myself;
    }

    public OptionalAssert<VALUE> isNotPresent() {
        return isEmpty();
    }

    public OptionalAssert<VALUE> contains(VALUE expectedValue) {
        isNotNull();
        if (actual.isEmpty()) {
            throw failure("\nExpecting Optional to contain:\n" + Shown.under(expectedValue) + "\nbut was empty.");
        }
        if (!Shown.same(actual.get(), expectedValue)) {
            throw unequal("\nExpecting actual:\n  " + actual + "\nto contain:\n" + Shown.under(expectedValue) + "\nbut did not.",
                    expectedValue, actual.get());
        }
        return myself;
    }

    public OptionalAssert<VALUE> hasValue(VALUE expectedValue) {
        return contains(expectedValue);
    }

    public OptionalAssert<VALUE> hasValueSatisfying(Consumer<VALUE> requirement) {
        isPresent();
        requirement.accept(actual.get());
        return myself;
    }

    @Uncollected
    public ObjectAssert<VALUE> get() {
        isPresent();
        return carried(new ObjectAssert<>(actual.get()));
    }

    @Override
    public RecursiveComparisonAssert<?> usingRecursiveComparison() {
        return super.usingRecursiveComparison();
    }
}

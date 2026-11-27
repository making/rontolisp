// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import rontolisp.internal.Introspected;
import rontolisp.internal.Shown;
import rontolisp.internal.Uncollected;

// The checks of an object: a value taken from it, by a function or by the name of a
// property or a field (rontolisp.internal.Introspected), is asserted on in its turn.
public abstract class AbstractObjectAssert<SELF extends AbstractObjectAssert<SELF, ACTUAL>, ACTUAL>
        extends AbstractAssert<SELF, ACTUAL> {

    protected AbstractObjectAssert(ACTUAL actual) {
        super(actual);
    }

    protected AbstractObjectAssert(ACTUAL actual, Class<?> selfType) {
        super(actual);
    }

    @Uncollected
    public <T> AbstractObjectAssert<?, T> extracting(Function<? super ACTUAL, T> extractor) {
        isNotNull();
        return carried(new ObjectAssert<>(extractor.apply(actual)));
    }

    public SELF hasFieldOrProperty(String name) {
        isNotNull();
        if (!Introspected.has(actual, name)) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have a property or a field named " + Shown.of(name)
                    + "\n(static and synthetic fields are ignored)");
        }
        return myself;
    }

    public SELF hasFieldOrPropertyWithValue(String name, Object value) {
        hasFieldOrProperty(name);
        Object held = Introspected.value(actual, name);
        if (!Shown.same(held, value)) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have a property or a field named " + Shown.of(name)
                    + " with value\n" + Shown.under(value) + "\nbut value was:\n" + Shown.under(held)
                    + "\n(static and synthetic fields are ignored)");
        }
        return myself;
    }

    @Uncollected
    public AbstractObjectAssert<?, ?> extracting(String propertyOrField) {
        isNotNull();
        return extracted(new ObjectAssert<>(Introspected.value(actual, propertyOrField)), propertyOrField);
    }

    @Uncollected
    public ListAssert<Object> extracting(String... propertiesOrFields) {
        isNotNull();
        List<Object> values = new ArrayList<>();
        for (String name : propertiesOrFields) {
            values.add(Introspected.value(actual, name));
        }
        return extracted(new ListAssert<>(values), String.join(", ", propertiesOrFields));
    }

    @Override
    public RecursiveComparisonAssert<?> usingRecursiveComparison() {
        return super.usingRecursiveComparison();
    }
}

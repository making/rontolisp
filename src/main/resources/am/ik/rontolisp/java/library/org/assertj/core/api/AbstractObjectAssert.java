// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.function.Function;

// The checks of an object: a value taken from it is asserted on in its turn.
public abstract class AbstractObjectAssert<SELF extends AbstractObjectAssert<SELF, ACTUAL>, ACTUAL>
        extends AbstractAssert<SELF, ACTUAL> {

    protected AbstractObjectAssert(ACTUAL actual) {
        super(actual);
    }

    protected AbstractObjectAssert(ACTUAL actual, Class<?> selfType) {
        super(actual);
    }

    public <T> AbstractObjectAssert<?, T> extracting(Function<? super ACTUAL, T> extractor) {
        isNotNull();
        ObjectAssert<T> extracted = new ObjectAssert<>(extractor.apply(actual));
        if (descriptionText() != null) {
            extracted.as(descriptionText());
        }
        return extracted;
    }
}

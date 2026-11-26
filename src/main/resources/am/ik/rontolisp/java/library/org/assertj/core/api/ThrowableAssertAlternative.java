// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

// The checks of a throwable a piece of code threw, under the names that read on from
// "is thrown by".
public class ThrowableAssertAlternative<ACTUAL extends Throwable>
        extends AbstractThrowableAssert<ThrowableAssertAlternative<ACTUAL>, ACTUAL> {

    public ThrowableAssertAlternative(ACTUAL actual) {
        super(actual);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessage(String message) {
        return hasMessage(message);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessage(String message, Object... parameters) {
        return hasMessage(message, parameters);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageContaining(String description) {
        return hasMessageContaining(description);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageNotContaining(String content) {
        return hasMessageNotContaining(content);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageStartingWith(String description) {
        return hasMessageStartingWith(description);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageEndingWith(String description) {
        return hasMessageEndingWith(description);
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageMatching(String regex) {
        return hasMessageMatching(regex);
    }

    public ThrowableAssertAlternative<ACTUAL> withNoCause() {
        return hasNoCause();
    }

    public ThrowableAssertAlternative<ACTUAL> withCauseInstanceOf(Class<? extends Throwable> type) {
        return hasCauseInstanceOf(type);
    }

    public ThrowableAssertAlternative<ACTUAL> withRootCauseInstanceOf(Class<? extends Throwable> type) {
        return hasRootCauseInstanceOf(type);
    }

    public AbstractThrowableAssert<?, ?> havingCause() {
        return cause();
    }

    public AbstractThrowableAssert<?, ?> havingRootCause() {
        return rootCause();
    }
}

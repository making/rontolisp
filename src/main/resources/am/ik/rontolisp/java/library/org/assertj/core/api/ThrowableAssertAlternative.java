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

    // The assertion whose checks the ones reading on from "is thrown by" are: this one,
    // or a soft assertion of the throwable.
    protected AbstractThrowableAssert<?, ACTUAL> getDelegate() {
        return this;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessage(String message) {
        getDelegate().hasMessage(message);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessage(String message, Object... parameters) {
        getDelegate().hasMessage(message, parameters);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageContaining(String description) {
        getDelegate().hasMessageContaining(description);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageNotContaining(String content) {
        getDelegate().hasMessageNotContaining(content);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageStartingWith(String description) {
        getDelegate().hasMessageStartingWith(description);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageEndingWith(String description) {
        getDelegate().hasMessageEndingWith(description);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withMessageMatching(String regex) {
        getDelegate().hasMessageMatching(regex);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withNoCause() {
        getDelegate().hasNoCause();
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withCauseInstanceOf(Class<? extends Throwable> type) {
        getDelegate().hasCauseInstanceOf(type);
        return myself;
    }

    public ThrowableAssertAlternative<ACTUAL> withRootCauseInstanceOf(Class<? extends Throwable> type) {
        getDelegate().hasRootCauseInstanceOf(type);
        return myself;
    }

    public AbstractThrowableAssert<?, ?> havingCause() {
        return cause();
    }

    public AbstractThrowableAssert<?, ?> havingRootCause() {
        return rootCause();
    }
}

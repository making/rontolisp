// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import rontolisp.internal.Soft;

// The checks of what a piece of code threw, as a soft assertion: those reading on from
// "is thrown by" are of a soft assertion of the throwable.
public class SoftThrowableAssertAlternative<ACTUAL extends Throwable> extends ThrowableAssertAlternative<ACTUAL> {

    private final SoftAssertionsProvider softAssertionsProvider;

    public SoftThrowableAssertAlternative(ACTUAL actual, SoftAssertionsProvider softAssertionsProvider) {
        super(actual);
        this.softAssertionsProvider = softAssertionsProvider;
    }

    // Described as this one is where it is asked for.
    @Override
    protected ThrowableAssert<ACTUAL> getDelegate() {
        return Soft.soft(carried(new ThrowableAssert<>(actual)), softAssertionsProvider);
    }
}

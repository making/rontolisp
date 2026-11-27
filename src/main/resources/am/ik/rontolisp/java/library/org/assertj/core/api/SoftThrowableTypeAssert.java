// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

// The class a piece of code is expected to throw an instance of, as a soft assertion:
// what it throws instead, or its throwing nothing, is a failure collected, and the
// checks that follow are of what it threw.
public class SoftThrowableTypeAssert<T extends Throwable> extends ThrowableTypeAssert<T> {

    private final SoftAssertionsProvider softAssertionsProvider;

    public SoftThrowableTypeAssert(Class<? extends T> throwableType, SoftAssertionsProvider softAssertionsProvider) {
        super(throwableType);
        this.softAssertionsProvider = softAssertionsProvider;
    }

    @Override
    protected ThrowableAssertAlternative<T> buildThrowableTypeAssert(T throwable) {
        return new SoftThrowableAssertAlternative<>(throwable, softAssertionsProvider);
    }

    @Override
    protected void checkThrowableType(Throwable throwable) {
        try {
            super.checkThrowableType(throwable);
        }
        catch (AssertionError error) {
            softAssertionsProvider.collectAssertionError(error);
        }
    }
}

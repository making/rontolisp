// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

public class ThrowableAssert<ACTUAL extends Throwable> extends AbstractThrowableAssert<ThrowableAssert<ACTUAL>, ACTUAL> {

    // A piece of code that may throw anything.
    public interface ThrowingCallable {

        void call() throws Throwable;
    }

    public ThrowableAssert(ACTUAL actual) {
        super(actual);
    }

    // What a piece of code throws, or null where it throws nothing.
    public static Throwable catchThrowable(ThrowingCallable shouldRaiseThrowable) {
        try {
            shouldRaiseThrowable.call();
        }
        catch (Throwable thrown) {
            return thrown;
        }
        return null;
    }
}

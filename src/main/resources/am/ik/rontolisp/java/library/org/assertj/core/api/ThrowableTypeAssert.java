// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import rontolisp.internal.Shown;

// The class a piece of code is expected to throw an instance of.
public class ThrowableTypeAssert<T extends Throwable> {

    protected final Class<? extends T> expectedThrowableType;

    private String description;

    public ThrowableTypeAssert(Class<? extends T> throwableType) {
        this.expectedThrowableType = throwableType;
    }

    public ThrowableTypeAssert<T> as(String description, Object... args) {
        this.description = args.length == 0 ? description : String.format(description, args);
        return this;
    }

    public ThrowableTypeAssert<T> describedAs(String description, Object... args) {
        return as(description, args);
    }

    @SuppressWarnings("unchecked")
    public ThrowableAssertAlternative<T> isThrownBy(ThrowingCallable throwingCallable) {
        Throwable thrown = ThrowableAssert.catchThrowable(throwingCallable);
        String prefix = description == null ? "" : "[" + description + "] ";
        if (thrown == null) {
            throw new AssertionError(prefix + "\nExpecting code to raise a throwable.");
        }
        if (!expectedThrowableType.isInstance(thrown)) {
            throw new AssertionError(prefix + "\nExpecting actual throwable to be an instance of:\n  "
                    + expectedThrowableType.getName() + "\nbut was:\n  " + Shown.thrown(thrown).replace("\n", "\n  "));
        }
        ThrowableAssertAlternative<T> checked = new ThrowableAssertAlternative<>((T) thrown);
        if (description != null) {
            checked.as(description);
        }
        return checked;
    }
}

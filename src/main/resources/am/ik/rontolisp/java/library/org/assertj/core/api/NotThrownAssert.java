// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

// A piece of code expected to throw nothing.
public class NotThrownAssert {

    private String description;

    public NotThrownAssert as(String description, Object... args) {
        this.description = args.length == 0 ? description : String.format(description, args);
        return this;
    }

    public NotThrownAssert describedAs(String description, Object... args) {
        return as(description, args);
    }

    public void isThrownBy(ThrowingCallable code) {
        ThrowableAssert<Throwable> thrown = new ThrowableAssert<>(ThrowableAssert.catchThrowable(code));
        if (description != null) {
            thrown.as(description);
        }
        thrown.doesNotThrowAnyException();
    }
}

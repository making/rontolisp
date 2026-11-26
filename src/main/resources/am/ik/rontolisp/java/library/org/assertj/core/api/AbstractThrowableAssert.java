// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.opentest4j.AssertionFailedError;
import rontolisp.internal.Shown;

// The checks of a throwable: its class, its message, its causes.
public abstract class AbstractThrowableAssert<SELF extends AbstractThrowableAssert<SELF, ACTUAL>, ACTUAL extends Throwable>
        extends AbstractObjectAssert<SELF, ACTUAL> {

    protected AbstractThrowableAssert(ACTUAL actual) {
        super(actual);
    }

    // The throwable under what a failed check says of it.
    private String checked() {
        return "\n\nThrowable that failed the check:\n\n" + Shown.thrown(actual) + "\n";
    }

    @Override
    protected String notAnInstance(Class<?> type) {
        return "\nExpecting actual throwable to be an instance of:\n  " + type.getName() + "\nbut was:\n  "
                + Shown.thrown(actual).replace("\n", "\n  ");
    }

    @Override
    protected String notExactly(Class<?> type) {
        return "\nExpecting actual throwable to be exactly an instance of:\n  " + type.getName() + "\nbut was:\n  "
                + Shown.thrown(actual).replace("\n", "\n  ");
    }

    public SELF hasMessage(String message) {
        isNotNull();
        String said = actual.getMessage();
        if (said == null ? message != null : !said.equals(message)) {
            throw unequal("\nExpecting message to be:\n" + Shown.under(message) + "\nbut was:\n" + Shown.under(said) + checked(),
                    message, said);
        }
        return myself;
    }

    public SELF hasMessage(String message, Object... parameters) {
        return hasMessage(parameters.length == 0 ? message : String.format(message, parameters));
    }

    public SELF hasMessageContaining(String description) {
        isNotNull();
        String said = actual.getMessage();
        if (said == null || !said.contains(description)) {
            throw failure("\nExpecting throwable message:\n" + Shown.under(said) + "\nto contain:\n" + Shown.under(description)
                    + "\nbut did not." + checked());
        }
        return myself;
    }

    public SELF hasMessageContaining(String description, Object... parameters) {
        return hasMessageContaining(parameters.length == 0 ? description : String.format(description, parameters));
    }

    public SELF hasMessageNotContaining(String content) {
        isNotNull();
        String said = actual.getMessage();
        if (said != null && said.contains(content)) {
            throw failure("\nExpecting throwable message:\n" + Shown.under(said) + "\nnot to contain:\n" + Shown.under(content)
                    + "\nbut did:" + checked());
        }
        return myself;
    }

    public SELF hasMessageStartingWith(String description) {
        isNotNull();
        String said = actual.getMessage();
        if (said == null || !said.startsWith(description)) {
            throw failure("\nExpecting actual:\n" + Shown.under(said) + "\nto start with:\n" + Shown.under(description) + "\n");
        }
        return myself;
    }

    public SELF hasMessageEndingWith(String description) {
        isNotNull();
        String said = actual.getMessage();
        if (said == null || !said.endsWith(description)) {
            throw failure("\nExpecting actual:\n" + Shown.under(said) + "\nto end with:\n" + Shown.under(description) + "\n");
        }
        return myself;
    }

    public SELF hasMessageMatching(String regex) {
        isNotNull();
        String said = actual.getMessage();
        if (said == null || !said.matches(regex)) {
            throw failure("\nExpecting message:\n" + Shown.under(said) + "\nto match regex:\n" + Shown.under(regex)
                    + "\nbut did not." + checked());
        }
        return myself;
    }

    public SELF hasNoCause() {
        isNotNull();
        if (actual.getCause() != null) {
            throw failure("\nExpecting exception without cause, but cause was: " + Shown.thrown(actual.getCause()) + "\n");
        }
        return myself;
    }

    public SELF hasCauseInstanceOf(Class<? extends Throwable> type) {
        isNotNull();
        Throwable cause = actual.getCause();
        if (cause == null) {
            throw failure("\nExpecting a throwable with cause being an instance of:\n  " + type.getName()
                    + "\nbut current throwable has no cause.\nThrowable that failed the check:\n" + Shown.thrown(actual) + "\n");
        }
        if (!type.isInstance(cause)) {
            throw failure("\nExpecting a throwable with cause being an instance of:\n  " + type.getName()
                    + "\nbut was an instance of:\n  " + cause.getClass().getName() + "\nThrowable that failed the check:\n\n"
                    + Shown.thrown(actual) + "\n");
        }
        return myself;
    }

    private Throwable root() {
        Throwable root = actual.getCause();
        int depth = 0;
        while (root != null && root.getCause() != null && depth++ < 64) {
            root = root.getCause();
        }
        return root;
    }

    public SELF hasRootCauseInstanceOf(Class<? extends Throwable> type) {
        isNotNull();
        Throwable root = root();
        if (root == null) {
            throw failure("\nExpecting a throwable with root cause being an instance of:\n  " + type.getName()
                    + "\nbut current throwable has no cause.\nThrowable that failed the check:\n" + Shown.thrown(actual) + "\n");
        }
        if (!type.isInstance(root)) {
            throw failure("\nExpecting a throwable with root cause being an instance of:\n  " + type.getName()
                    + "\nbut was an instance of:\n  " + root.getClass().getName() + "\n\nThrowable that failed the check:\n"
                    + Shown.thrown(actual) + "\n");
        }
        return myself;
    }

    public SELF hasRootCauseMessage(String message) {
        isNotNull();
        Throwable root = root();
        if (root == null) {
            throw failure("\nExpecting a root cause with message:\n" + Shown.under(message) + "\nbut actual had no root cause.");
        }
        String said = root.getMessage();
        if (said == null ? message != null : !said.equals(message)) {
            throw unequal("\nExpecting a root cause with message:\n" + Shown.under(message) + "\nbut message was:\n"
                    + Shown.under(said) + ".\nThrowable that failed the check:\n\n" + Shown.thrown(actual) + "\n", message, said);
        }
        return myself;
    }

    public AbstractThrowableAssert<?, ?> cause() {
        isNotNull();
        if (actual.getCause() == null) {
            throw failure("Expecting actual throwable to have a cause but it did not, actual was:\n" + Shown.thrown(actual) + "\n");
        }
        return new ThrowableAssert<>(actual.getCause());
    }

    public AbstractThrowableAssert<?, ?> rootCause() {
        isNotNull();
        Throwable root = root();
        if (root == null) {
            throw failure("Expecting actual throwable to have a root cause but it did not, actual was:\n" + Shown.thrown(actual)
                    + "\n");
        }
        return new ThrowableAssert<>(root);
    }

    // The check of a piece of code that must throw nothing: the throwable is what it threw.
    public void doesNotThrowAnyException() {
        if (actual != null) {
            throw failure("\nExpecting code not to raise a throwable but caught\n  \"" + Shown.thrown(actual).replace("\n", "\n  ")
                    + "\n\"");
        }
    }
}

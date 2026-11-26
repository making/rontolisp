// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.opentest4j.AssertionFailedError;
import rontolisp.internal.Shown;

// What every assertion has: the value, what the assertion is described as, and the
// checks any value takes.
public abstract class AbstractAssert<SELF extends AbstractAssert<SELF, ACTUAL>, ACTUAL> {

    protected final ACTUAL actual;

    protected final SELF myself;

    private String description;

    private String overriding;

    @SuppressWarnings("unchecked")
    protected AbstractAssert(ACTUAL actual) {
        this.actual = actual;
        this.myself = (SELF) this;
    }

    @SuppressWarnings("unchecked")
    protected AbstractAssert(ACTUAL actual, Class<?> selfType) {
        this.actual = actual;
        this.myself = (SELF) this;
    }

    // ---- what a failure says

    private String said(String message) {
        return (description == null ? "" : "[" + description + "] ") + (overriding != null ? overriding : message);
    }

    // A check that failed.
    protected AssertionError failure(String message) {
        return new AssertionError(said(message));
    }

    // A check of the value against an expected one that failed.
    protected AssertionError unequal(String message, Object expected, Object value) {
        if (overriding != null) {
            return new AssertionError(said(message));
        }
        return new AssertionFailedError(said(message), expected, value);
    }

    protected void failWithMessage(String errorMessage, Object... arguments) {
        throw failure(arguments.length == 0 ? errorMessage : String.format(errorMessage, arguments));
    }

    protected String descriptionText() {
        return description;
    }

    public SELF as(String description, Object... args) {
        this.description = args.length == 0 ? description : String.format(description, args);
        return myself;
    }

    public SELF describedAs(String description, Object... args) {
        return as(description, args);
    }

    public SELF withFailMessage(String newErrorMessage, Object... args) {
        this.overriding = args.length == 0 ? newErrorMessage : String.format(newErrorMessage, args);
        return myself;
    }

    public SELF overridingErrorMessage(String newErrorMessage, Object... args) {
        return withFailMessage(newErrorMessage, args);
    }

    // ---- the checks of any value

    public SELF isEqualTo(Object expected) {
        if (!Shown.same(actual, expected)) {
            throw unequal("\nexpected: " + Shown.after(expected) + "\n but was: " + Shown.after(actual), expected, actual);
        }
        return myself;
    }

    public SELF isNotEqualTo(Object other) {
        if (Shown.same(actual, other)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be equal to:\n" + Shown.under(other) + "\n");
        }
        return myself;
    }

    public void isNull() {
        if (actual != null) {
            throw unequal("\nexpected: null\n but was: " + Shown.after(actual), null, actual);
        }
    }

    public SELF isNotNull() {
        if (actual == null) {
            throw failure("\nExpecting actual not to be null");
        }
        return myself;
    }

    public SELF isSameAs(Object expected) {
        if (actual != expected) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nand:\n" + Shown.under(expected)
                    + "\nto refer to the same object");
        }
        return myself;
    }

    public SELF isNotSameAs(Object other) {
        if (actual == other) {
            throw failure("\nExpected not same: " + Shown.of(actual));
        }
        return myself;
    }

    public SELF isInstanceOf(Class<?> type) {
        isNotNull();
        if (!type.isInstance(actual)) {
            throw failure(notAnInstance(type));
        }
        return myself;
    }

    // What is said of a value that is no instance of a type.
    protected String notAnInstance(Class<?> type) {
        return "\nExpecting actual:\n" + Shown.under(actual) + "\nto be an instance of:\n  " + type.getName()
                + "\nbut was instance of:\n  " + actual.getClass().getName();
    }

    public SELF isNotInstanceOf(Class<?> type) {
        isNotNull();
        if (type.isInstance(actual)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be an instance of: " + type.getName());
        }
        return myself;
    }

    public SELF isExactlyInstanceOf(Class<?> type) {
        isNotNull();
        if (!type.equals(actual.getClass())) {
            throw failure(notExactly(type));
        }
        return myself;
    }

    // What is said of a value whose class is not the one expected.
    protected String notExactly(Class<?> type) {
        return "\nExpecting actual:\n" + Shown.under(actual) + "\nto be exactly an instance of:\n  " + type.getName()
                + "\nbut was an instance of:\n  " + actual.getClass().getName();
    }

    @SuppressWarnings("unchecked")
    public <T> SELF isInstanceOfSatisfying(Class<T> type, Consumer<T> requirements) {
        isInstanceOf(type);
        requirements.accept((T) actual);
        return myself;
    }

    public SELF isIn(Object... values) {
        if (!Shown.holds(List.of(values), actual)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be in:\n" + Shown.under(values) + "\n");
        }
        return myself;
    }

    public SELF isNotIn(Object... values) {
        if (Shown.holds(List.of(values), actual)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be in:\n" + Shown.under(values) + "\n");
        }
        return myself;
    }

    public SELF hasToString(String expectedToString) {
        isNotNull();
        String text = actual.toString();
        if (!text.equals(expectedToString)) {
            throw unequal("\nExpecting actual's toString() to return:\n" + Shown.under(expectedToString) + "\nbut was:\n"
                    + Shown.under(text), expectedToString, text);
        }
        return myself;
    }

    public SELF matches(Predicate<? super ACTUAL> predicate) {
        if (!predicate.test(actual)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto match given predicate.");
        }
        return myself;
    }

    public SELF matches(Predicate<? super ACTUAL> predicate, String predicateDescription) {
        if (!predicate.test(actual)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto match '" + predicateDescription + "' predicate.");
        }
        return myself;
    }

    public SELF satisfies(Consumer<? super ACTUAL> requirements) {
        requirements.accept(actual);
        return myself;
    }

    public SELF doesNotHaveToString(String otherToString) {
        isNotNull();
        if (actual.toString().equals(otherToString)) {
            throw failure("\nExpecting actual's toString() not to be equal to:\n" + Shown.under(otherToString));
        }
        return myself;
    }
}

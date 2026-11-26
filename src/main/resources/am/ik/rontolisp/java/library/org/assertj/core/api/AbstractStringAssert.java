// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Shown;

// The checks of a text.
public abstract class AbstractStringAssert<SELF extends AbstractStringAssert<SELF>> extends AbstractAssert<SELF, String> {

    protected AbstractStringAssert(String actual) {
        super(actual);
    }

    private String expecting(String relation, Object other) {
        return "\nExpecting actual:\n" + Shown.under(actual) + "\n" + relation + ":\n" + Shown.under(other);
    }

    public void isNullOrEmpty() {
        if (actual != null && !actual.isEmpty()) {
            throw failure("\nExpecting null or empty but was: " + Shown.of(actual));
        }
    }

    public void isEmpty() {
        isNotNull();
        if (!actual.isEmpty()) {
            throw failure("\nExpecting empty but was: " + Shown.of(actual));
        }
    }

    public SELF isNotEmpty() {
        isNotNull();
        if (actual.isEmpty()) {
            throw failure("\nExpecting actual not to be empty");
        }
        return myself;
    }

    public SELF isBlank() {
        if (actual == null || !actual.isBlank()) {
            throw failure("\nExpecting blank but was: " + Shown.of(actual));
        }
        return myself;
    }

    public SELF isNotBlank() {
        if (actual == null || actual.isBlank()) {
            throw failure("\nExpecting not blank but was: " + Shown.of(actual));
        }
        return myself;
    }

    public SELF hasSize(int expected) {
        isNotNull();
        if (actual.length() != expected) {
            throw failure("\nExpected size: " + expected + " but was: " + actual.length() + " in:\n" + Shown.of(actual));
        }
        return myself;
    }

    private SELF sized(boolean holds, String relation, int boundary) {
        isNotNull();
        if (!holds) {
            throw failure("\nExpecting size of:\n" + Shown.under(actual) + "\nto be " + relation + " " + boundary + " but was "
                    + actual.length());
        }
        return myself;
    }

    public SELF hasSizeLessThan(int boundary) {
        return sized(actual == null || actual.length() < boundary, "less than", boundary);
    }

    public SELF hasSizeLessThanOrEqualTo(int boundary) {
        return sized(actual == null || actual.length() <= boundary, "less than or equal to", boundary);
    }

    public SELF hasSizeGreaterThan(int boundary) {
        return sized(actual == null || actual.length() > boundary, "greater than", boundary);
    }

    public SELF hasSizeGreaterThanOrEqualTo(int boundary) {
        return sized(actual == null || actual.length() >= boundary, "greater than or equal to", boundary);
    }

    public SELF contains(CharSequence... values) {
        isNotNull();
        List<Object> absent = new ArrayList<>();
        for (CharSequence value : values) {
            if (!actual.contains(value)) {
                absent.add(value.toString());
            }
        }
        if (!absent.isEmpty()) {
            if (values.length == 1) {
                throw failure(expecting("to contain", values[0].toString()) + " ");
            }
            List<Object> all = new ArrayList<>();
            for (CharSequence value : values) {
                all.add(value.toString());
            }
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto contain:\n" + Shown.under(all)
                    + "\nbut could not find:\n" + Shown.under(absent) + "\n ");
        }
        return myself;
    }

    public SELF doesNotContain(CharSequence... values) {
        isNotNull();
        for (CharSequence value : values) {
            if (actual.contains(value)) {
                throw failure(expecting("not to contain", value.toString()) + "\n");
            }
        }
        return myself;
    }

    public SELF containsIgnoringCase(CharSequence sequence) {
        isNotNull();
        if (!actual.toLowerCase().contains(sequence.toString().toLowerCase())) {
            throw failure(expecting("to contain", sequence.toString()) + "\n (ignoring case)");
        }
        return myself;
    }

    public SELF containsSubsequence(CharSequence... values) {
        isNotNull();
        int from = 0;
        for (CharSequence value : values) {
            int at = actual.indexOf(value.toString(), from);
            if (at < 0) {
                List<Object> all = new ArrayList<>();
                for (CharSequence each : values) {
                    all.add(each.toString());
                }
                throw failure("\nExpecting actual:\n" + Shown.under(actual)
                        + "\nto contain the following CharSequences in this order (possibly with other values between them):\n"
                        + Shown.under(all) + "\n");
            }
            from = at + value.length();
        }
        return myself;
    }

    public SELF startsWith(CharSequence prefix) {
        isNotNull();
        if (!actual.startsWith(prefix.toString())) {
            throw failure(expecting("to start with", prefix.toString()) + "\n");
        }
        return myself;
    }

    public SELF doesNotStartWith(CharSequence prefix) {
        isNotNull();
        if (actual.startsWith(prefix.toString())) {
            throw failure(expecting("not to start with", prefix.toString()) + "\n");
        }
        return myself;
    }

    public SELF endsWith(CharSequence suffix) {
        isNotNull();
        if (!actual.endsWith(suffix.toString())) {
            throw failure(expecting("to end with", suffix.toString()) + "\n");
        }
        return myself;
    }

    public SELF doesNotEndWith(CharSequence suffix) {
        isNotNull();
        if (actual.endsWith(suffix.toString())) {
            throw failure(expecting("not to end with", suffix.toString()) + "\n");
        }
        return myself;
    }

    public SELF isEqualToIgnoringCase(CharSequence expected) {
        if (actual == null ? expected != null : expected == null || !actual.equalsIgnoreCase(expected.toString())) {
            throw unequal(Shown.expected(expected == null ? null : expected.toString(), actual)
                    + "\nignoring case considerations", expected, actual);
        }
        return myself;
    }

    public SELF matches(CharSequence regex) {
        isNotNull();
        if (!actual.matches(regex.toString())) {
            throw failure(expecting("to match pattern", regex.toString()));
        }
        return myself;
    }

    public SELF doesNotMatch(CharSequence regex) {
        isNotNull();
        if (actual.matches(regex.toString())) {
            throw failure(expecting("not to match pattern", regex.toString()));
        }
        return myself;
    }
}

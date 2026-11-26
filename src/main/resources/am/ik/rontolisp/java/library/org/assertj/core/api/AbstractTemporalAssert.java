// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.temporal.Temporal;

import rontolisp.internal.Shown;

// The checks of a date or a time: its place on the time line, and what it reads on its
// fields. A value written as text is parsed as its class parses it.
public abstract class AbstractTemporalAssert<SELF extends AbstractTemporalAssert<SELF, TEMPORAL>, TEMPORAL extends Temporal>
        extends AbstractAssert<SELF, TEMPORAL> {

    protected AbstractTemporalAssert(TEMPORAL actual) {
        super(actual);
    }

    protected abstract TEMPORAL parse(String temporalAsString);

    // What a failure of an order says after the values: a line of the comparison made.
    String comparison() {
        return "\n";
    }

    @SuppressWarnings("unchecked")
    int compared(TEMPORAL other) {
        return ((Comparable<Object>) actual).compareTo(other);
    }

    // A value checked to be placed against another as it says.
    SELF placed(boolean holds, String relation, TEMPORAL other) {
        if (!holds) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be " + relation + ":\n" + Shown.under(other)
                    + comparison());
        }
        return myself;
    }

    SELF between(TEMPORAL start, TEMPORAL end, boolean inclusive) {
        isNotNull();
        int fromStart = compared(start);
        int toEnd = compared(end);
        if (inclusive ? fromStart < 0 || toEnd > 0 : fromStart <= 0 || toEnd >= 0) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be between:\n  " + (inclusive ? "[" : "]")
                    + Shown.of(start) + ", " + Shown.of(end) + (inclusive ? "]" : "[") + comparison());
        }
        return myself;
    }

    // A field of the value checked to read as expected.
    SELF on(String field, Object expected, Object had) {
        isNotNull();
        if (!expected.equals(had)) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be on \"" + field + "\" " + expected);
        }
        return myself;
    }

    public SELF isBefore(TEMPORAL other) {
        isNotNull();
        return placed(compared(other) < 0, "strictly before", other);
    }

    public SELF isBefore(String other) {
        return isBefore(parse(other));
    }

    public SELF isBeforeOrEqualTo(TEMPORAL other) {
        isNotNull();
        return placed(compared(other) <= 0, "before or equal to", other);
    }

    public SELF isBeforeOrEqualTo(String other) {
        return isBeforeOrEqualTo(parse(other));
    }

    public SELF isAfter(TEMPORAL other) {
        isNotNull();
        return placed(compared(other) > 0, "strictly after", other);
    }

    public SELF isAfter(String other) {
        return isAfter(parse(other));
    }

    public SELF isAfterOrEqualTo(TEMPORAL other) {
        isNotNull();
        return placed(compared(other) >= 0, "after or equal to", other);
    }

    public SELF isAfterOrEqualTo(String other) {
        return isAfterOrEqualTo(parse(other));
    }

    public SELF isEqualTo(String expected) {
        return isEqualTo(parse(expected));
    }

    public SELF isNotEqualTo(String other) {
        return isNotEqualTo(parse(other));
    }

    public SELF isIn(String... values) {
        return isIn(parsed(values));
    }

    public SELF isNotIn(String... values) {
        return isNotIn(parsed(values));
    }

    private java.util.List<Object> parsed(String[] values) {
        java.util.List<Object> parsed = new java.util.ArrayList<>();
        for (String value : values) {
            parsed.add(parse(value));
        }
        return parsed;
    }

    public SELF isBetween(TEMPORAL startInclusive, TEMPORAL endInclusive) {
        return between(startInclusive, endInclusive, true);
    }

    public SELF isBetween(String startInclusive, String endInclusive) {
        return between(parse(startInclusive), parse(endInclusive), true);
    }

    public SELF isStrictlyBetween(TEMPORAL startExclusive, TEMPORAL endExclusive) {
        return between(startExclusive, endExclusive, false);
    }

    public SELF isStrictlyBetween(String startExclusive, String endExclusive) {
        return between(parse(startExclusive), parse(endExclusive), false);
    }
}

// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.assertj.core.data.Offset;
import rontolisp.internal.Shown;

// The checks of a value that has an order.
public abstract class AbstractComparableAssert<SELF extends AbstractComparableAssert<SELF, ACTUAL>, ACTUAL extends Comparable<? super ACTUAL>>
        extends AbstractObjectAssert<SELF, ACTUAL> {

    protected AbstractComparableAssert(ACTUAL actual) {
        super(actual);
    }

    private String expecting(String relation, Object other, String end) {
        return "\nExpecting actual:\n" + Shown.under(actual) + "\nto be " + relation + ":\n" + Shown.under(other) + end;
    }

    public SELF isGreaterThan(ACTUAL other) {
        isNotNull();
        if (!(actual.compareTo(other) > 0)) {
            throw failure(expecting("greater than", other, "\n"));
        }
        return myself;
    }

    public SELF isGreaterThanOrEqualTo(ACTUAL other) {
        isNotNull();
        if (!(actual.compareTo(other) >= 0)) {
            throw failure(expecting("greater than or equal to", other, "\n"));
        }
        return myself;
    }

    public SELF isLessThan(ACTUAL other) {
        isNotNull();
        if (!(actual.compareTo(other) < 0)) {
            throw failure(expecting("less than", other, " "));
        }
        return myself;
    }

    public SELF isLessThanOrEqualTo(ACTUAL other) {
        isNotNull();
        if (!(actual.compareTo(other) <= 0)) {
            throw failure(expecting("less than or equal to", other, " "));
        }
        return myself;
    }

    public SELF isBetween(ACTUAL startInclusive, ACTUAL endInclusive) {
        isNotNull();
        if (actual.compareTo(startInclusive) < 0 || actual.compareTo(endInclusive) > 0) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be between:\n  [" + Shown.of(startInclusive)
                    + ", " + Shown.of(endInclusive) + "]\n");
        }
        return myself;
    }

    public SELF isStrictlyBetween(ACTUAL startExclusive, ACTUAL endExclusive) {
        isNotNull();
        if (actual.compareTo(startExclusive) <= 0 || actual.compareTo(endExclusive) >= 0) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be between:\n  ]" + Shown.of(startExclusive)
                    + ", " + Shown.of(endExclusive) + "[\n");
        }
        return myself;
    }

    public SELF isEqualByComparingTo(ACTUAL other) {
        isNotNull();
        if (actual.compareTo(other) != 0) {
            throw unequal(Shown.expected(other, actual), other, actual);
        }
        return myself;
    }

    public SELF isNotEqualByComparingTo(ACTUAL other) {
        isNotNull();
        if (actual.compareTo(other) == 0) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be equal to:\n" + Shown.under(other) + "\n");
        }
        return myself;
    }

    // A number checked to be close to another, or not, by a difference already measured.
    SELF closeTo(ACTUAL expected, Offset<?> offset, Number difference, boolean close, boolean wanted) {
        if (close != wanted) {
            boolean valid = offset.strict != wanted;
            String by = Shown.of(offset.value);
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + (wanted ? "\nto be close to:\n" : "\nnot to be close to:\n")
                    + Shown.under(expected) + "\nby less than " + by + " but difference was " + Shown.of(difference)
                    + ".\n(a difference of exactly " + (wanted ? by : "<" + by + ">") + " being considered "
                    + (valid ? "valid" : "invalid") + ")");
        }
        return myself;
    }

    // The offset a check is given, refused where there is none.
    static <T extends Number> Offset<T> given(Offset<T> offset) {
        return java.util.Objects.requireNonNull(offset, "The given offset should not be null");
    }
}

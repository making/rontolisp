// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.Duration;

import rontolisp.internal.Shown;

// The checks of a duration: its sign, its length in a unit, its distance from another.
public abstract class AbstractDurationAssert<SELF extends AbstractDurationAssert<SELF>> extends AbstractComparableAssert<SELF, Duration> {

    protected AbstractDurationAssert(Duration actual) {
        super(actual);
    }

    public SELF isZero() {
        return isEqualTo(Duration.ZERO);
    }

    public SELF isNegative() {
        return isLessThan(Duration.ZERO);
    }

    public SELF isPositive() {
        return isGreaterThan(Duration.ZERO);
    }

    // A duration checked to last a number of a unit.
    private SELF lasts(long expected, long had, String unit) {
        if (had != expected) {
            throw unequal("\nExpecting Duration:\n" + Shown.under(actual) + "\nto have " + Shown.of(expected) + " " + unit
                    + (expected == 1 ? "" : "s") + " but had " + Shown.of(had), expected, had);
        }
        return myself;
    }

    public SELF hasNanos(long otherNanos) {
        isNotNull();
        return lasts(otherNanos, actual.toNanos(), "nano");
    }

    public SELF hasMillis(long otherMillis) {
        isNotNull();
        return lasts(otherMillis, actual.toMillis(), "milli");
    }

    public SELF hasSeconds(long otherSeconds) {
        isNotNull();
        return lasts(otherSeconds, actual.getSeconds(), "second");
    }

    public SELF hasMinutes(long otherMinutes) {
        isNotNull();
        return lasts(otherMinutes, actual.toMinutes(), "minute");
    }

    public SELF hasHours(long otherHours) {
        isNotNull();
        return lasts(otherHours, actual.toHours(), "hour");
    }

    public SELF hasDays(long otherDays) {
        isNotNull();
        return lasts(otherDays, actual.toDays(), "day");
    }

    public SELF isCloseTo(Duration expected, Duration allowedDifference) {
        isNotNull();
        Duration difference = actual.minus(expected).abs();
        if (difference.compareTo(allowedDifference) > 0) {
            throw failure("\nExpecting actual:\n  " + actual + "\nto be close to:\n  " + expected + "\nwithin "
                    + allowedDifference + " but difference was " + difference);
        }
        return myself;
    }
}

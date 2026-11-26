// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.assertj.core.data.Offset;

// The checks of an int: against a number written as it is.
public abstract class AbstractIntegerAssert<SELF extends AbstractIntegerAssert<SELF>> extends AbstractComparableAssert<SELF, Integer> {

    protected AbstractIntegerAssert(Integer actual) {
        super(actual);
    }

    public SELF isEqualTo(int expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(int other) {
        return isNotEqualTo((Object) other);
    }

    public SELF isZero() {
        return isEqualTo(0);
    }

    public SELF isNotZero() {
        return isNotEqualTo(0);
    }

    public SELF isOne() {
        return isEqualTo(1);
    }

    public SELF isPositive() {
        return isGreaterThan(0);
    }

    public SELF isNegative() {
        return isLessThan(0);
    }

    public SELF isNotNegative() {
        return isGreaterThanOrEqualTo(0);
    }

    public SELF isNotPositive() {
        return isLessThanOrEqualTo(0);
    }

    public SELF isGreaterThan(int other) {
        return isGreaterThan((Integer) other);
    }

    public SELF isGreaterThanOrEqualTo(int other) {
        return isGreaterThanOrEqualTo((Integer) other);
    }

    public SELF isLessThan(int other) {
        return isLessThan((Integer) other);
    }

    public SELF isLessThanOrEqualTo(int other) {
        return isLessThanOrEqualTo((Integer) other);
    }

    public SELF isCloseTo(int expected, Offset<Integer> offset) {
        return isCloseTo((Integer) expected, offset);
    }

    public SELF isCloseTo(Integer expected, Offset<Integer> offset) {
        return closeTo(expected, offset, true);
    }

    public SELF isNotCloseTo(int expected, Offset<Integer> offset) {
        return isNotCloseTo((Integer) expected, offset);
    }

    public SELF isNotCloseTo(Integer expected, Offset<Integer> offset) {
        return closeTo(expected, offset, false);
    }

    private SELF closeTo(Integer expected, Offset<Integer> offset, boolean wanted) {
        given(offset);
        isNotNull();
        int difference = Math.abs(actual - expected);
        return closeTo(expected, offset, difference, offset.strict ? difference < offset.value : difference <= offset.value,
                wanted);
    }
}

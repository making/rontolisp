// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

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
}

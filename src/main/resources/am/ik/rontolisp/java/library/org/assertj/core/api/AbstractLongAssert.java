// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import org.assertj.core.data.Offset;

// The checks of a long: against a number written as it is.
public abstract class AbstractLongAssert<SELF extends AbstractLongAssert<SELF>> extends AbstractComparableAssert<SELF, Long> {

    protected AbstractLongAssert(Long actual) {
        super(actual);
    }

    public SELF isEqualTo(long expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(long other) {
        return isNotEqualTo((Object) other);
    }

    public SELF isZero() {
        return isEqualTo(0L);
    }

    public SELF isNotZero() {
        return isNotEqualTo(0L);
    }

    public SELF isOne() {
        return isEqualTo(1L);
    }

    public SELF isPositive() {
        return isGreaterThan(0L);
    }

    public SELF isNegative() {
        return isLessThan(0L);
    }

    public SELF isNotNegative() {
        return isGreaterThanOrEqualTo(0L);
    }

    public SELF isNotPositive() {
        return isLessThanOrEqualTo(0L);
    }

    public SELF isGreaterThan(long other) {
        return isGreaterThan((Long) other);
    }

    public SELF isGreaterThanOrEqualTo(long other) {
        return isGreaterThanOrEqualTo((Long) other);
    }

    public SELF isLessThan(long other) {
        return isLessThan((Long) other);
    }

    public SELF isLessThanOrEqualTo(long other) {
        return isLessThanOrEqualTo((Long) other);
    }

    public SELF isCloseTo(long expected, Offset<Long> offset) {
        return isCloseTo((Long) expected, offset);
    }

    public SELF isCloseTo(Long expected, Offset<Long> offset) {
        return closeTo(expected, offset, true);
    }

    public SELF isNotCloseTo(long expected, Offset<Long> offset) {
        return isNotCloseTo((Long) expected, offset);
    }

    public SELF isNotCloseTo(Long expected, Offset<Long> offset) {
        return closeTo(expected, offset, false);
    }

    private SELF closeTo(Long expected, Offset<Long> offset, boolean wanted) {
        given(offset);
        isNotNull();
        long difference = Math.abs(actual - expected);
        return closeTo(expected, offset, difference, offset.strict ? difference < offset.value : difference <= offset.value,
                wanted);
    }
}

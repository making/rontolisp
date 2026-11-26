// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.math.BigDecimal;

import org.assertj.core.data.Offset;

// The checks of a float: against a number written as it is.
public abstract class AbstractFloatAssert<SELF extends AbstractFloatAssert<SELF>> extends AbstractComparableAssert<SELF, Float> {

    protected AbstractFloatAssert(Float actual) {
        super(actual);
    }

    public SELF isEqualTo(float expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(float other) {
        return isNotEqualTo((Object) other);
    }

    public SELF isZero() {
        return isEqualTo(0.0f);
    }

    public SELF isNotZero() {
        return isNotEqualTo(0.0f);
    }

    public SELF isOne() {
        return isEqualTo(1.0f);
    }

    public SELF isPositive() {
        return isGreaterThan(0.0f);
    }

    public SELF isNegative() {
        return isLessThan(0.0f);
    }

    public SELF isNotNegative() {
        return isGreaterThanOrEqualTo(0.0f);
    }

    public SELF isNotPositive() {
        return isLessThanOrEqualTo(0.0f);
    }

    public SELF isGreaterThan(float other) {
        return isGreaterThan((Float) other);
    }

    public SELF isGreaterThanOrEqualTo(float other) {
        return isGreaterThanOrEqualTo((Float) other);
    }

    public SELF isLessThan(float other) {
        return isLessThan((Float) other);
    }

    public SELF isLessThanOrEqualTo(float other) {
        return isLessThanOrEqualTo((Float) other);
    }

    public SELF isCloseTo(float expected, Offset<Float> offset) {
        return isCloseTo((Float) expected, offset);
    }

    public SELF isCloseTo(Float expected, Offset<Float> offset) {
        return closeTo(expected, offset, true);
    }

    public SELF isNotCloseTo(float expected, Offset<Float> offset) {
        return isNotCloseTo((Float) expected, offset);
    }

    public SELF isNotCloseTo(Float expected, Offset<Float> offset) {
        return closeTo(expected, offset, false);
    }

    public SELF isEqualTo(float expected, Offset<Float> offset) {
        return isCloseTo((Float) expected, offset);
    }

    public SELF isEqualTo(Float expected, Offset<Float> offset) {
        return isCloseTo(expected, offset);
    }

    private SELF closeTo(Float expected, Offset<Float> offset, boolean wanted) {
        given(offset);
        isNotNull();
        // a difference of finite numbers is measured in decimal, as their texts read
        float one = actual;
        float other = expected;
        float difference = one == other ? 0.0f
                : Float.isFinite(one) && Float.isFinite(other)
                        ? new BigDecimal(Float.toString(one)).subtract(new BigDecimal(Float.toString(other))).abs().floatValue()
                        : Math.abs(one - other);
        return closeTo(expected, offset, difference, offset.strict ? difference < offset.value : difference <= offset.value,
                wanted);
    }
}

// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.math.BigDecimal;

import org.assertj.core.data.Offset;

// The checks of a double: against a number written as it is.
public abstract class AbstractDoubleAssert<SELF extends AbstractDoubleAssert<SELF>> extends AbstractComparableAssert<SELF, Double> {

    protected AbstractDoubleAssert(Double actual) {
        super(actual);
    }

    public SELF isEqualTo(double expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(double other) {
        return isNotEqualTo((Object) other);
    }

    public SELF isZero() {
        return isEqualTo(0.0);
    }

    public SELF isNotZero() {
        return isNotEqualTo(0.0);
    }

    public SELF isOne() {
        return isEqualTo(1.0);
    }

    public SELF isPositive() {
        return isGreaterThan(0.0);
    }

    public SELF isNegative() {
        return isLessThan(0.0);
    }

    public SELF isNotNegative() {
        return isGreaterThanOrEqualTo(0.0);
    }

    public SELF isNotPositive() {
        return isLessThanOrEqualTo(0.0);
    }

    public SELF isGreaterThan(double other) {
        return isGreaterThan((Double) other);
    }

    public SELF isGreaterThanOrEqualTo(double other) {
        return isGreaterThanOrEqualTo((Double) other);
    }

    public SELF isLessThan(double other) {
        return isLessThan((Double) other);
    }

    public SELF isLessThanOrEqualTo(double other) {
        return isLessThanOrEqualTo((Double) other);
    }

    public SELF isNaN() {
        return isEqualTo(Double.NaN);
    }

    public SELF isNotNaN() {
        return isNotEqualTo(Double.NaN);
    }

    public SELF isCloseTo(double expected, Offset<Double> offset) {
        return isCloseTo((Double) expected, offset);
    }

    public SELF isCloseTo(Double expected, Offset<Double> offset) {
        return closeTo(expected, offset, true);
    }

    public SELF isNotCloseTo(double expected, Offset<Double> offset) {
        return isNotCloseTo((Double) expected, offset);
    }

    public SELF isNotCloseTo(Double expected, Offset<Double> offset) {
        return closeTo(expected, offset, false);
    }

    public SELF isEqualTo(double expected, Offset<Double> offset) {
        return isCloseTo((Double) expected, offset);
    }

    public SELF isEqualTo(Double expected, Offset<Double> offset) {
        return isCloseTo(expected, offset);
    }

    private SELF closeTo(Double expected, Offset<Double> offset, boolean wanted) {
        given(offset);
        isNotNull();
        // a difference of finite numbers is measured in decimal, as their texts read
        double one = actual;
        double other = expected;
        double difference = one == other ? 0.0
                : Double.isFinite(one) && Double.isFinite(other)
                        ? BigDecimal.valueOf(one).subtract(BigDecimal.valueOf(other)).abs().doubleValue()
                        : Math.abs(one - other);
        return closeTo(expected, offset, difference, offset.strict ? difference < offset.value : difference <= offset.value,
                wanted);
    }
}

// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.LocalDateTime;
import java.time.Month;

import rontolisp.internal.Shown;

// A date and time is compared on the time line, which each failure says.
public abstract class AbstractLocalDateTimeAssert<SELF extends AbstractLocalDateTimeAssert<SELF>>
        extends AbstractTemporalAssert<SELF, LocalDateTime> {

    protected AbstractLocalDateTimeAssert(LocalDateTime actual) {
        super(actual);
    }

    @Override
    protected LocalDateTime parse(String localDateTimeAsString) {
        return LocalDateTime.parse(localDateTimeAsString);
    }

    @Override
    String comparison() {
        return "\nwhen comparing values using 'ChronoLocalDateTime.timeLineOrder()'";
    }

    @Override
    public SELF isEqualTo(Object expected) {
        if (actual == null || expected == null) {
            return super.isEqualTo(expected);
        }
        if (!(expected instanceof LocalDateTime other) || compared(other) != 0) {
            throw unequal(Shown.expected(expected, actual) + comparison(), expected, actual);
        }
        return myself;
    }

    @Override
    public SELF isNotEqualTo(Object other) {
        if (actual == null || other == null) {
            return super.isNotEqualTo(other);
        }
        if (other instanceof LocalDateTime time && compared(time) == 0) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be equal to:\n" + Shown.under(other)
                    + comparison());
        }
        return myself;
    }

    public SELF hasYear(int year) {
        return on("year", year, actual == null ? null : actual.getYear());
    }

    public SELF hasMonth(Month month) {
        return on("month", month, actual == null ? null : actual.getMonth());
    }

    public SELF hasMonthValue(int month) {
        return on("month", month, actual == null ? null : actual.getMonthValue());
    }

    public SELF hasDayOfMonth(int dayOfMonth) {
        return on("day of month", dayOfMonth, actual == null ? null : actual.getDayOfMonth());
    }

    public SELF hasHour(int hour) {
        return on("hour", hour, actual == null ? null : actual.getHour());
    }

    public SELF hasMinute(int minute) {
        return on("minute", minute, actual == null ? null : actual.getMinute());
    }

    public SELF hasSecond(int second) {
        return on("second", second, actual == null ? null : actual.getSecond());
    }

    public SELF hasNano(int nano) {
        return on("nanosecond", nano, actual == null ? null : actual.getNano());
    }
}

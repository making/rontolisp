// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.LocalDate;
import java.time.Month;

public abstract class AbstractLocalDateAssert<SELF extends AbstractLocalDateAssert<SELF>> extends AbstractTemporalAssert<SELF, LocalDate> {

    protected AbstractLocalDateAssert(LocalDate actual) {
        super(actual);
    }

    @Override
    protected LocalDate parse(String localDateAsString) {
        return LocalDate.parse(localDateAsString);
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
        return on("day", dayOfMonth, actual == null ? null : actual.getDayOfMonth());
    }
}

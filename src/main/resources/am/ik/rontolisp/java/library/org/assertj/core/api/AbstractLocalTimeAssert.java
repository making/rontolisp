// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.LocalTime;

public abstract class AbstractLocalTimeAssert<SELF extends AbstractLocalTimeAssert<SELF>> extends AbstractTemporalAssert<SELF, LocalTime> {

    protected AbstractLocalTimeAssert(LocalTime actual) {
        super(actual);
    }

    @Override
    protected LocalTime parse(String localTimeAsString) {
        return LocalTime.parse(localTimeAsString);
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
        return on("nano", nano, actual == null ? null : actual.getNano());
    }
}

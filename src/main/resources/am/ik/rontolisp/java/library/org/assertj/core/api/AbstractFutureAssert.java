// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import rontolisp.internal.Shown;

// The checks of a future: its state, and its result once it has one within a time.
public abstract class AbstractFutureAssert<SELF extends AbstractFutureAssert<SELF, ACTUAL, RESULT>, ACTUAL extends Future<RESULT>, RESULT>
        extends AbstractAssert<SELF, ACTUAL> {

    protected AbstractFutureAssert(ACTUAL actual) {
        super(actual);
    }

    // A check of the state of a future, which a failure shows as it is when it is said.
    static String stated(Future<?> future, String expectation) {
        return "\nExpecting\n  <" + state(future) + ">\n" + expectation + ".\nBe aware that the state of the future in this"
                + " message might not reflect the one at the time when the assertion was performed as it is evaluated"
                + " later on";
    }

    // A future as a failure shows it: a completable one by its state.
    static String state(Future<?> future) {
        if (!(future instanceof CompletableFuture<?> completable)) {
            return String.valueOf(future);
        }
        if (!completable.isDone()) {
            return "CompletableFuture[Incomplete]";
        }
        if (completable.isCancelled()) {
            return "CompletableFuture[Cancelled]";
        }
        if (completable.isCompletedExceptionally()) {
            return "CompletableFuture[Failed with the following stack trace:\n" + traced(completable.exceptionNow()) + "]";
        }
        return "CompletableFuture[Completed: " + Shown.of(completable.resultNow()) + "]";
    }

    static String traced(Throwable thrown) {
        StringWriter text = new StringWriter();
        thrown.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    SELF holds(boolean holds, String expectation) {
        isNotNull();
        if (!holds) {
            throw failure(stated(actual, expectation));
        }
        return myself;
    }

    public SELF isDone() {
        isNotNull();
        return holds(actual.isDone(), "to be done");
    }

    public SELF isNotDone() {
        isNotNull();
        return holds(!actual.isDone(), "not to be done");
    }

    public SELF isCancelled() {
        isNotNull();
        return holds(actual.isCancelled(), "to be cancelled");
    }

    public SELF isNotCancelled() {
        isNotNull();
        return holds(!actual.isCancelled(), "not to be cancelled");
    }

    // The result of a future got within a time, else a failure saying what was caught.
    static <T> T within(AbstractAssert<?, ?> check, Future<T> future, long timeout, TimeUnit unit, String time) {
        try {
            return future.get(timeout, unit);
        }
        catch (Exception caught) {
            throw check.failure("\nExpecting\n  <" + state(future) + ">\nto be completed within " + time
                    + ".\n\nexception caught while trying to get the future result: " + traced(caught));
        }
    }

    static String unit(TimeUnit unit) {
        return switch (unit) {
            case NANOSECONDS -> "Nanos";
            case MICROSECONDS -> "Micros";
            case MILLISECONDS -> "Millis";
            case SECONDS -> "Seconds";
            case MINUTES -> "Minutes";
            case HOURS -> "Hours";
            case DAYS -> "Days";
        };
    }

    public ObjectAssert<RESULT> succeedsWithin(Duration timeout) {
        isNotNull();
        return carried(new ObjectAssert<>(within(this, actual, timeout.toNanos(), TimeUnit.NANOSECONDS, Shown.of(timeout))));
    }

    public ObjectAssert<RESULT> succeedsWithin(long timeout, TimeUnit unit) {
        isNotNull();
        return carried(new ObjectAssert<>(within(this, actual, timeout, unit, Shown.of(timeout) + " " + unit(unit))));
    }
}

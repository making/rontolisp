// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import rontolisp.internal.Shown;

// The checks of a completable future: how it completed, and with what.
public abstract class AbstractCompletableFutureAssert<SELF extends AbstractCompletableFutureAssert<SELF, RESULT>, RESULT>
        extends AbstractAssert<SELF, CompletableFuture<RESULT>> {

    protected AbstractCompletableFutureAssert(CompletableFuture<RESULT> actual) {
        super(actual);
    }

    private SELF holds(boolean holds, String expectation) {
        isNotNull();
        if (!holds) {
            throw failure(AbstractFutureAssert.stated(actual, expectation));
        }
        return myself;
    }

    private boolean completed() {
        return actual.isDone() && !actual.isCompletedExceptionally();
    }

    public SELF isDone() {
        isNotNull();
        return holds(actual.isDone(), "to be done");
    }

    public SELF isNotDone() {
        isNotNull();
        return holds(!actual.isDone(), "not to be done");
    }

    public SELF isCompleted() {
        isNotNull();
        return holds(completed(), "to be completed");
    }

    public SELF isNotCompleted() {
        isNotNull();
        return holds(!completed(), "not to be completed");
    }

    public SELF isCompletedExceptionally() {
        isNotNull();
        return holds(actual.isCompletedExceptionally(), "to be completed exceptionally");
    }

    public SELF isNotCompletedExceptionally() {
        isNotNull();
        return holds(!actual.isCompletedExceptionally(), "to not be completed exceptionally");
    }

    public SELF isCancelled() {
        isNotNull();
        return holds(actual.isCancelled(), "to be cancelled");
    }

    public SELF isNotCancelled() {
        isNotNull();
        return holds(!actual.isCancelled(), "not to be cancelled");
    }

    @Deprecated
    public SELF hasFailed() {
        isNotNull();
        return holds(actual.isCompletedExceptionally() && !actual.isCancelled(),
                "to have failed (i.e. completed exceptionally and not cancelled)");
    }

    public SELF isCompletedWithValue(RESULT expected) {
        isCompleted();
        RESULT value = actual.join();
        if (!Shown.same(value, expected)) {
            throw unequal(Shown.expected(expected, value), expected, value);
        }
        return myself;
    }

    public SELF isCompletedWithValueMatching(Predicate<? super RESULT> predicate) {
        isCompleted();
        RESULT value = actual.join();
        if (!predicate.test(value)) {
            throw failure("\nExpecting actual:\n" + Shown.under(value) + "\nto match given predicate.\n\nYou can use"
                    + " 'matches(Predicate p, String description)' to have a better error message\nFor example:\n"
                    + "  assertThat(player).matches(p -> p.isRookie(), \"is rookie\");\nwill give an error message"
                    + " looking like:\n\nExpecting actual:\n  player\nto match 'is rookie' predicate");
        }
        return myself;
    }

    public SELF isCompletedWithValueMatching(Predicate<? super RESULT> predicate, String description) {
        isCompleted();
        RESULT value = actual.join();
        if (!predicate.test(value)) {
            throw failure("\nExpecting actual:\n" + Shown.under(value) + "\nto match '" + description + "' predicate.");
        }
        return myself;
    }

    public ObjectAssert<RESULT> succeedsWithin(Duration timeout) {
        isNotNull();
        return carried(new ObjectAssert<>(AbstractFutureAssert.within(this, actual, timeout.toNanos(), TimeUnit.NANOSECONDS,
                Shown.of(timeout))));
    }

    public ObjectAssert<RESULT> succeedsWithin(long timeout, TimeUnit unit) {
        isNotNull();
        return carried(new ObjectAssert<>(AbstractFutureAssert.within(this, actual, timeout, unit,
                Shown.of(timeout) + " " + AbstractFutureAssert.unit(unit))));
    }
}

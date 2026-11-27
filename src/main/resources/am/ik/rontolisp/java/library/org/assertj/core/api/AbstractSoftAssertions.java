// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.error.AssertJMultipleFailuresError;
import rontolisp.internal.Soft;

// What every provider of soft assertions has: the failures collected, thrown together by
// assertAll, and the failures of the test's own.
public abstract class AbstractSoftAssertions extends DefaultAssertionErrorCollector
        implements SoftAssertionsProvider, InstanceOfAssertFactories {

    protected AbstractSoftAssertions() {
    }

    public static void assertAll(AssertionErrorCollector collector) {
        List<AssertionError> errors = collector.assertionErrorsCollected();
        if (!errors.isEmpty()) {
            throw new AssertJMultipleFailuresError(null, errors);
        }
    }

    @Override
    public void assertAll() {
        assertAll(this);
    }

    // An assertion of a class of the program's, made by its constructor taking the value.
    @Override
    public <SELF extends AbstractAssert<? extends SELF, ? extends ACTUAL>, ACTUAL> SELF proxy(Class<SELF> assertClass,
            Class<ACTUAL> actualClass, ACTUAL actual) {
        try {
            Constructor<SELF> constructor = assertClass.getDeclaredConstructor(actualClass);
            constructor.setAccessible(true);
            return Soft.soft(constructor.newInstance(actual), this);
        }
        catch (ReflectiveOperationException refused) {
            throw new RuntimeException(refused);
        }
    }

    public <T> T fail(String failureMessage) {
        collectAssertionError(new AssertionError(failureMessage));
        return null;
    }

    public <T> T fail() {
        return fail("");
    }

    public <T> T fail(String failureMessage, Object... args) {
        return fail(args.length == 0 ? failureMessage : String.format(failureMessage, args));
    }

    public <T> T fail(String failureMessage, Throwable realCause) {
        collectAssertionError(new AssertionError(failureMessage, realCause));
        return null;
    }

    public <T> T fail(Throwable realCause) {
        return fail("", realCause);
    }

    public void failBecauseExceptionWasNotThrown(Class<? extends Throwable> throwableClass) {
        shouldHaveThrown(throwableClass);
    }

    public void shouldHaveThrown(Class<? extends Throwable> throwableClass) {
        collectAssertionError(new AssertionError(throwableClass.getSimpleName() + " should have been thrown"));
    }

    public List<Throwable> errorsCollected() {
        return new ArrayList<>(assertionErrorsCollected());
    }
}

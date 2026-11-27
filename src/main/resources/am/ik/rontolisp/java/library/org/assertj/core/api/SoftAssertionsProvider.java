// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

// What makes soft assertions: assertions whose failures it collects, thrown together
// when asked.
public interface SoftAssertionsProvider extends AssertionErrorCollector {

    // A piece of code that may throw an exception.
    interface ThrowingRunnable {

        void run() throws Exception;
    }

    <SELF extends AbstractAssert<? extends SELF, ? extends ACTUAL>, ACTUAL> SELF proxy(Class<SELF> assertClass,
            Class<ACTUAL> actualClass, ACTUAL actual);

    void assertAll();

    default void assertAlso(AssertionErrorCollector collector) {
        for (AssertionError error : collector.assertionErrorsCollected()) {
            collectAssertionError(error);
        }
    }

    default void check(ThrowingRunnable assertion) {
        try {
            assertion.run();
            succeeded();
        }
        catch (AssertionError error) {
            collectAssertionError(error);
        }
        catch (RuntimeException thrown) {
            throw thrown;
        }
        catch (Exception thrown) {
            throw new RuntimeException(thrown);
        }
    }
}

// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AssertionErrorCollector;

// A soft assertion: an assertion with a collector, to which a check of it that fails
// hands its failure. A program that names this class makes soft assertions, and only in
// such a program has an assertion a place for a collector and does the front end make
// every check of an assertion call what is here (JavaLowering.softCheck). Measured of
// AssertJ 3.27 under a JDK: the chain goes on past a check that failed, and a check
// called by a check of any soft assertion is part of that one, which fails where it
// does.
public final class Soft {

    // Whether a check of a soft assertion is running: a check starting now is part of it.
    private static boolean running;

    // Whether the check that ran last handed a failure over.
    private static boolean failing;

    private Soft() {
    }

    // The collector of an assertion, null for one that throws its failures.
    private static native Object collector0(Object assertion);

    private static native void collect0(Object assertion, Object collector);

    // An assertion made a soft one: its failures go to the collector.
    public static <A extends AbstractAssert<?, ?>> A soft(A assertion, AssertionErrorCollector collector) {
        collect0(assertion, collector);
        return assertion;
    }

    // Soft assertions handing their failures to a collector: those a run of tests gives a
    // test, as AssertJ's extension does.
    public static <P extends AssertionErrorCollector> P delegating(P provider, AssertionErrorCollector collector) {
        provider.setDelegate(collector);
        return provider;
    }

    // Whether a check starting now is the one running: its assertion has a collector and
    // no check of a soft assertion is running.
    public static boolean begins(Object assertion) {
        if (running || collector0(assertion) == null) {
            return false;
        }
        running = true;
        return true;
    }

    // The check running failed: what the collector tells of a failure is no part of it.
    public static void failed(Object assertion, AssertionError failure) {
        running = false;
        ((AssertionErrorCollector) collector0(assertion)).collectAssertionError(failure);
        failing = true;
    }

    // The check running is over; one that handed no failure over passed.
    public static void ended(Object assertion) {
        running = false;
        if (failing) {
            failing = false;
        }
        else {
            ((AssertionErrorCollector) collector0(assertion)).succeeded();
        }
    }

    // The method running, which is no check, is over.
    public static void left() {
        running = false;
    }

    // What a method of a soft assertion answered. Another assertion goes on as a soft one
    // of the same collector, described as the first is and no other way (measured: where
    // the assertion is no soft one, what is extracted or an element says where it came
    // from).
    public static <T> T answered(AbstractAssert<?, ?> assertion, T answer) {
        if (answer instanceof AbstractAssert<?, ?> other && other != assertion) {
            collect0(other, collector0(assertion));
            other.as(assertion.descriptionText());
        }
        return answer;
    }
}

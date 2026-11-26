// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.platform.commons.PreconditionViolationException;

// One run of a @RepeatedTest: its place among the runs, how many of the runs before it
// failed, and its display name, the pattern of the annotation's name with the test's
// display name, then the place, then the count put in. The checks of the annotation and
// their words are as JUnit Jupiter answered them, run under a JDK.
public final class TestRepetition implements RepetitionInfo {

    private final int current;

    private final int total;

    private final int failures;

    private final int threshold;

    private final String displayName;

    public TestRepetition(int current, int total, int failures, int threshold, String pattern, String displayName) {
        this.current = current;
        this.total = total;
        this.failures = failures;
        this.threshold = threshold;
        this.displayName = pattern.replace(RepeatedTest.DISPLAY_NAME_PLACEHOLDER, displayName)
            .replace(RepeatedTest.CURRENT_REPETITION_PLACEHOLDER, String.valueOf(current))
            .replace(RepeatedTest.TOTAL_REPETITIONS_PLACEHOLDER, String.valueOf(total));
    }

    // What is wrong with how a test is repeated, or null: method is the test's method as
    // a reflected method writes itself.
    public static Throwable check(int total, String pattern, int threshold, String method) {
        String wrong = null;
        if (total <= 0) {
            wrong = "must be declared with a positive 'value'.";
        }
        else if (threshold != Integer.MAX_VALUE && (threshold <= 0 || threshold >= total)) {
            wrong = "must declare a 'failureThreshold' greater than zero and less than the total number of "
                    + "repetitions [" + total + "].";
        }
        else if (pattern.isBlank()) {
            wrong = "must be declared with a non-empty name.";
        }
        return wrong == null ? null
                : new PreconditionViolationException(
                        "Configuration error: @RepeatedTest on method [" + method + "] " + wrong);
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public int getCurrentRepetition() {
        return current;
    }

    @Override
    public int getTotalRepetitions() {
        return total;
    }

    @Override
    public int getFailureCount() {
        return failures;
    }

    @Override
    public int getFailureThreshold() {
        return threshold;
    }
}

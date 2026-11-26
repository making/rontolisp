// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import org.opentest4j.TestAbortedException;

// What `rontolisp test` runs a file's tests on: each test is counted and run, what it
// throws is a failure where it is an AssertionError, a skip where an assumption did not
// hold (TestAbortedException) and an error where it is anything else, and each class is
// reported when its tests are over. The status is 1 when a test failed or none ran.
public final class TestRun {

    // A test, or what runs around the tests of a class.
    @FunctionalInterface
    public interface Body {

        void run() throws Throwable;
    }

    // The class whose tests are running, inside the class around it.
    private static final class Running {

        final String name;

        final boolean own;

        final Running around;

        int run;

        int failures;

        int errors;

        int skipped;

        final StringBuilder problems = new StringBuilder();

        Running(String name, boolean own, Running around) {
            this.name = name;
            this.own = own;
            this.around = around;
        }
    }

    private Running running;

    private int totalRun;

    private int totalFailures;

    private int totalErrors;

    private int totalSkipped;

    // A class starts: one with tests of its own is said to run, one that only holds
    // classes with tests is reported where something of its own failed.
    public void started(String className, boolean own) {
        running = new Running(className, own, running);
        if (own) {
            System.out.println("Running " + className);
        }
    }

    // Runs one test: its name is the method's, with the place of its arguments among
    // the ones it is run with where it has parameters.
    public void test(String name, String displayName, Body body) {
        running.run++;
        try {
            body.run();
        }
        catch (Throwable thrown) {
            failed(name, displayName, thrown);
        }
    }

    // Runs what a class runs before or after all its tests: what it throws is an error
    // of the class, and an assumption that does not hold skips the tests it was to run
    // around, each test and each nested class one, as surefire counts them.
    public boolean around(Body body, int tests) {
        try {
            body.run();
            return true;
        }
        catch (Throwable thrown) {
            if (thrown instanceof TestAbortedException) {
                skipped(tests);
            }
            else {
                running.run++;
                failed(null, null, thrown);
            }
            return false;
        }
    }

    // What one more throwable makes of what was thrown before it: the first, with the
    // later one suppressed, but that a failure goes before an assumption that did not
    // hold.
    public static Throwable then(Throwable thrown, Throwable caught) {
        if (thrown == null) {
            return caught;
        }
        if (thrown instanceof TestAbortedException && !(caught instanceof TestAbortedException)) {
            caught.addSuppressed(thrown);
            return caught;
        }
        thrown.addSuppressed(caught);
        return thrown;
    }

    // A test that could not be run at all: its arguments could not be made.
    public void broken(String name, String displayName, Throwable thrown) {
        running.run++;
        failed(name, displayName, thrown);
    }

    private void failed(String name, String displayName, Throwable thrown) {
        if (thrown instanceof TestAbortedException) {
            running.skipped++;
            return;
        }
        boolean failure = thrown instanceof AssertionError;
        if (failure) {
            running.failures++;
        }
        else {
            running.errors++;
        }
        StringBuilder problems = running.problems;
        problems.append(running.name);
        if (name != null) {
            problems.append('.').append(name);
        }
        if (displayName != null) {
            problems.append(" -- ").append(displayName);
        }
        problems.append(failure ? " <<< FAILURE!\n" : " <<< ERROR!\n");
        String said = failure ? thrown.getMessage() : Shown.thrown(thrown);
        problems.append(said == null ? thrown.getClass().getName() : said).append('\n');
    }

    // Tests that do not run: a disabled test, or the tests of a disabled class, each
    // test and each nested class one.
    public void skipped(int tests) {
        running.run += tests;
        running.skipped += tests;
    }

    public void finished() {
        Running over = running;
        running = over.around;
        if (over.own || over.run > 0) {
            System.out.println(counts(over.run, over.failures, over.errors, over.skipped)
                    + (over.failures + over.errors > 0 ? " <<< FAILURE!" : "") + " -- in " + over.name);
            System.out.print(over.problems);
        }
        totalRun += over.run;
        totalFailures += over.failures;
        totalErrors += over.errors;
        totalSkipped += over.skipped;
    }

    private static String counts(int run, int failures, int errors, int skipped) {
        return "Tests run: " + run + ", Failures: " + failures + ", Errors: " + errors + ", Skipped: " + skipped;
    }

    // The totals, and the status: 1 when a test failed or none ran.
    public int finish(String target) {
        if (totalRun == 0) {
            System.err.println("rontolisp test: no tests were run for " + target);
            return 1;
        }
        System.out.println();
        System.out.println("Results:");
        System.out.println();
        System.out.println(counts(totalRun, totalFailures, totalErrors, totalSkipped));
        return totalFailures + totalErrors > 0 ? 1 : 0;
    }
}

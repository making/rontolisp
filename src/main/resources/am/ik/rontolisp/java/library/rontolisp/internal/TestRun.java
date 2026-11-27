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

    // Runs one run of a repeated test, named by its place among the runs, and answers
    // whether it failed: an assumption that did not hold is no failure. A method of its
    // own, so that a run with no repeated test holds none of it.
    public boolean repetition(String name, String displayName, Body body) {
        running.run++;
        try {
            body.run();
            return false;
        }
        catch (Throwable thrown) {
            failed(name, displayName, thrown);
            return !(thrown instanceof TestAbortedException);
        }
    }

    // A test of a class whose tests run in an order of its own, or a @Nested class of
    // one whose classes do: what it is sorted by, and what runs it.
    public static final class Ordered {

        final int order;

        final String name;

        final String parameters;

        final Runnable body;

        // order: its @Order, for MethodOrderer.OrderAnnotation; name: its method's name,
        // or its display name for MethodOrderer.DisplayName; parameters: its parameters'
        // types, what equal names are sorted by.
        public Ordered(int order, String name, String parameters, Runnable body) {
            this.order = order;
            this.name = name;
            this.parameters = parameters;
            this.body = body;
        }
    }

    // Runs the tests of a class in the order a MethodOrderer of JUnit's gives, or its
    // @Nested classes in the order of a ClassOrderer: by their @Order, equal ones in the
    // order declared, or by their names, then by their parameters. byOrder: whether by
    // their @Order.
    public static void ordered(java.util.List<Ordered> tests, boolean byOrder) {
        java.util.List<Ordered> sorted = new java.util.ArrayList<>(tests);
        sorted.sort(byOrder ? (a, b) -> Integer.compare(a.order, b.order) : (a, b) -> {
            int names = a.name.compareTo(b.name);
            return names != 0 ? names : a.parameters.compareTo(b.parameters);
        });
        for (Ordered each : sorted) {
            each.body.run();
        }
    }

    // Runs the tests of a class, or its @Nested classes, in the pseudo-random order of
    // JUnit's Random orderers, whose seed is the clock's where a run configures none, as
    // their documentation states.
    public static void shuffled(java.util.List<Ordered> tests) {
        java.util.List<Ordered> shuffled = new java.util.ArrayList<>(tests);
        java.util.Collections.shuffle(shuffled, new java.util.Random(System.nanoTime()));
        for (Ordered each : shuffled) {
            each.body.run();
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

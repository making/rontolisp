package am.ik.rontolisp.testsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.error.AssertJMultipleFailuresError;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The oracle of {@code rontolisp test FILE.java}: a program that runs test classes on the
 * JUnit Platform, with JUnit Jupiter and AssertJ themselves, and reports them in the
 * shape the Java subset's own runner ({@code rontolisp.internal.TestRun}) reports them
 * in, so that one expectation holds what the frameworks do and what the subset's part of
 * them does. Started in a JVM of its own by the test that compares the two, with the
 * classes of the file under test on its class path; its arguments are their binary names,
 * in the order the file declares them.
 *
 * <p>
 * JUnit runs the tests of a class in an order of its own, the runner in the order
 * declared: the problems of a class are reported here sorted by test, so an expectation
 * holds for both where a class declares its failing tests in the order of their names.
 */
public final class JavaTestOracle {

	private JavaTestOracle() {
	}

	/** What is counted of one class. */
	private static final class Running {

		int run;

		int failures;

		int errors;

		int skipped;

		/** How many of the tests and nested classes in it started or were skipped. */
		int started;

		final List<String[]> problems = new ArrayList<>();

	}

	private static int totalRun;

	private static int totalFailures;

	private static int totalErrors;

	private static int totalSkipped;

	/**
	 * Runs the classes and exits with the runner's status: 1 when a test failed or none
	 * ran.
	 * @param args the binary names of the test classes
	 * @throws ClassNotFoundException for a name that is no class
	 */
	public static void main(String[] args) throws ClassNotFoundException {
		// the classes are run as JUnit runs them by default, whatever the configuration
		// of
		// the suite that starts this program says: its properties file is on the class
		// path
		LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
			.enableImplicitConfigurationParameters(false);
		for (String name : args) {
			request.selectors(selectClass(Class.forName(name)));
		}
		Map<String, Running> running = new HashMap<>();
		LauncherFactory.create().execute(request.build(), new TestExecutionListener() {
			private @Nullable TestPlan plan;

			@Override
			public void testPlanExecutionStarted(TestPlan testPlan) {
				this.plan = testPlan;
			}

			private TestPlan plan() {
				return java.util.Objects.requireNonNull(this.plan);
			}

			private boolean aborted(TestExecutionResult result) {
				return result.getStatus() == TestExecutionResult.Status.ABORTED;
			}

			private boolean isClass(TestIdentifier id) {
				return id.getSource().filter(ClassSource.class::isInstance).isPresent();
			}

			private String className(TestIdentifier id) {
				return ((ClassSource) id.getSource().orElseThrow()).getClassName();
			}

			/** The class a test or a parameterized test's container is of. */
			private TestIdentifier owner(TestIdentifier id) {
				TestIdentifier at = id;
				while (!isClass(at)) {
					at = plan().getParent(at).orElseThrow();
				}
				return at;
			}

			/** Whether a class has tests of its own, and not only classes with some. */
			private boolean own(TestIdentifier type) {
				return plan().getChildren(type).stream().anyMatch(child -> !isClass(child));
			}

			@Override
			public void executionStarted(TestIdentifier id) {
				plan().getParent(id)
					.map(parent -> running.get(parent.getUniqueId()))
					.ifPresent(parent -> parent.started++);
				if (isClass(id)) {
					running.put(id.getUniqueId(), new Running());
					if (own(id)) {
						System.out.println("Running " + className(id));
					}
				}
			}

			@Override
			public void executionSkipped(TestIdentifier id, String reason) {
				plan().getParent(id)
					.map(parent -> running.get(parent.getUniqueId()))
					.ifPresent(parent -> parent.started++);
				if (isClass(id)) {
					// each test and each nested class of a disabled class is a skipped
					// test, as surefire counts them
					System.out.println("Running " + className(id));
					Running skipped = new Running();
					skipped.run = plan().getChildren(id).size();
					skipped.skipped = skipped.run;
					report(className(id), skipped, true);
					return;
				}
				Running counted = java.util.Objects.requireNonNull(running.get(owner(id).getUniqueId()));
				counted.run++;
				counted.skipped++;
			}

			@Override
			public void executionFinished(TestIdentifier id, TestExecutionResult result) {
				Optional<Throwable> thrown = result.getThrowable();
				if (isClass(id)) {
					Running counted = java.util.Objects.requireNonNull(running.get(id.getUniqueId()));
					if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
						// an assumption of what runs around the tests that did not hold:
						// each test and nested class that did not start is skipped, as
						// surefire counts them
						int unstarted = plan().getChildren(id).size() - counted.started;
						counted.run += unstarted;
						counted.skipped += unstarted;
					}
					else if (thrown.isPresent()) {
						counted.run++;
						problem(counted, className(id), null, null, thrown.get());
					}
					report(className(id), counted, own(id));
					return;
				}
				if (id.getSource().filter(MethodSource.class::isInstance).isEmpty()) {
					return;
				}
				TestIdentifier type = owner(id);
				Running counted = java.util.Objects.requireNonNull(running.get(type.getUniqueId()));
				MethodSource method = (MethodSource) id.getSource().orElseThrow();
				TestIdentifier declared = id;
				String name = method.getMethodName();
				TestIdentifier parent = plan().getParent(id).orElseThrow();
				String kind = id.getUniqueIdObject().getLastSegment().getType();
				boolean dynamic = kind.startsWith("dynamic-");
				if (!isClass(parent)) {
					// one run of a parameterized test: its place among the runs; a test
					// or a container a factory made: its place in each container around
					declared = dynamic ? id : parent;
					String legacy = id.getLegacyReportingName();
					name += legacy.substring(legacy.lastIndexOf(')') + 1);
				}
				if (id.isContainer()
						&& (thrown.isEmpty() || (dynamic || kind.equals("test-factory")) && aborted(result))) {
					// a parameterized test or a factory whose tests were counted one by
					// one; a factory or one of its containers whose assumption did not
					// hold counts nothing, as surefire counts it
					return;
				}
				String displayed = declared.getDisplayName().equals(declared.getLegacyReportingName()) ? null
						: declared.getDisplayName();
				counted.run++;
				if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
					counted.skipped++;
				}
				else if (thrown.isPresent()) {
					problem(counted, className(type), name, displayed, thrown.get());
				}
			}
		});
		if (totalRun == 0) {
			System.err.println("no tests were run");
			System.exit(1);
		}
		System.out.println();
		System.out.println("Results:");
		System.out.println();
		System.out.println(counts(totalRun, totalFailures, totalErrors, totalSkipped));
		System.exit(totalFailures + totalErrors > 0 ? 1 : 0);
	}

	private static void problem(Running counted, String className, @Nullable String name, @Nullable String displayed,
			Throwable thrown) {
		boolean failure = thrown instanceof AssertionError;
		if (failure) {
			counted.failures++;
		}
		else {
			counted.errors++;
		}
		StringBuilder said = new StringBuilder(className);
		if (name != null) {
			said.append('.').append(name);
		}
		if (displayed != null) {
			said.append(" -- ").append(displayed);
		}
		said.append(failure ? " <<< FAILURE!\n" : " <<< ERROR!\n");
		if (failure) {
			said.append(thrown.getMessage() == null ? thrown.getClass().getName() : message(thrown));
		}
		else {
			said.append(thrown);
			for (Throwable cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
				said.append("\nCaused by: ").append(cause);
			}
		}
		// sorted by the method: the runs of a parameterized test and what a factory made
		// are in the order they ran in, and what the class itself threw is last, as it is
		// known when its tests are over
		String method = name == null ? "\uffff" : name.replaceFirst("\\[.*$", "");
		counted.problems.add(new String[] { method, said.append('\n').toString() });
	}

	/**
	 * What a failure says. AssertJ ends the message of each failure that soft assertions
	 * collected, and of each that a group of requirements threw, with a line naming the
	 * test's file and line ({@code at Class.method(File.java:12)}); the runner knows no
	 * line of a failure, so those lines are left out of what the two are compared by.
	 */
	private static String message(Throwable thrown) {
		String message = java.util.Objects.requireNonNull(thrown.getMessage());
		return thrown instanceof AssertJMultipleFailuresError ? message.replaceAll("\nat [^\n]*\\.java:\\d+\\)", "")
				: message;
	}

	private static void report(String className, Running counted, boolean own) {
		if (own || counted.run > 0) {
			System.out.println(counts(counted.run, counted.failures, counted.errors, counted.skipped)
					+ (counted.failures + counted.errors > 0 ? " <<< FAILURE!" : "") + " -- in " + className);
			counted.problems.stream()
				.sorted(java.util.Comparator.comparing(problem -> problem[0]))
				.forEach(problem -> System.out.print(problem[1]));
		}
		totalRun += counted.run;
		totalFailures += counted.failures;
		totalErrors += counted.errors;
		totalSkipped += counted.skipped;
	}

	private static String counts(int run, int failures, int errors, int skipped) {
		return "Tests run: " + run + ", Failures: " + failures + ", Errors: " + errors + ", Skipped: " + skipped;
	}

}

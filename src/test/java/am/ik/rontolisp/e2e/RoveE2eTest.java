package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The rove milestone gate ({@code .kb/asdf.md}): Eitaro Fukamachi's rove v0.10.0 (BSD
 * 3-Clause, quicklisp dist rove-20260101-git) and dissect (zlib) are vendored UNMODIFIED
 * under {@code src/test/resources/rove} and {@code src/test/resources/dissect}, load via
 * {@code asdf:load-system} next to the already-vendored cl-ppcre, and a test suite in the
 * shape of rove's README + {@code examples/passed.lisp} runs with the spec reporter on
 * ALL FOUR backends via {@link AsdfLibraryE2eSupport}. The demo project
 * ({@code src/test/resources/rove-demo}) exercises BOTH system shapes rove dispatches on:
 * {@code my-app/tests} is a {@code :package-inferred-system} (the jsonrpc style -- the
 * suite is found through {@code asdf:component-sideway-dependencies} and the derived
 * sub-system metaobjects), and {@code my-plain/tests} is a plain {@code defsystem} whose
 * suite is found through the {@code *load-pathname*}-keyed file-to-package map recorded
 * at {@code deftest} time. All the entry points run: {@code rove:run} on both systems,
 * {@code rove:run-test} on a single test symbol, and {@code rove:run-suite} on
 * {@code *package*} (the README FAQ style), each returning the passed-p boolean the
 * {@code (uiop:quit (if (rove:run ...) 0 1))} exit recipe needs -- pinned via the
 * {@code ... passed: yes/no} lines.
 *
 * <p>
 * The test file walks the assertion surface: {@code deftest} / {@code testing} /
 * {@code ok} / {@code ng} / {@code signals} with a user condition AND {@code 'type-error}
 * / {@code outputs} / {@code pass} / {@code fail} / {@code skip} / {@code failing} /
 * {@code setup} / {@code teardown} / {@code defhook} / {@code diag}, a deliberately
 * failing assertion, and an assertion whose form SIGNALS mid-evaluation (the
 * {@code handler-bind} seam that turns a broken test into a recorded failure instead of
 * ending the run -- which is also why no test body here uses a form that raw-TRAPS on the
 * wasm backends, e.g. {@code (car 1)} or {@code (/ 1 0)}: a trap still ends a wasm run,
 * the documented spectrum of {@code .kb/error-handling.md}).
 *
 * <p>
 * Every expected line was verified against SBCL 2.2.9 on the same sources modulo four
 * normalizations, each pinned to its cause: (1) {@code  (Nms)} duration suffixes are
 * stripped from the ACTUAL output ({@link #normalizeLine(String)}) -- rove prints them
 * for any assertion over 37 ms and an interpreter run can cross that; (2) SBCL's
 * {@code at file:line:column} source-location lines are absent -- rove computes them
 * under {@code #+sbcl} only; (3) failure backtraces are absent -- dissect's {@code stack}
 * is the empty no-op interface on every rontolisp backend (nil'd on SBCL for the
 * comparison). Since 2026-09-02 the printer consults the runtime {@code *package*}
 * accessibility (CLHS 22.1.3.3.1, {@code .kb/pretty-printer.md}), so an accessible symbol
 * prints unqualified exactly as on SBCL: {@code ADD} while {@code run} has
 * {@code *package*} bound to the test package that uses {@code my-app/main}, and
 * {@code MY-APP/MAIN:ADD} where {@code *package*} is still {@code cl-user} -- the
 * {@code run-test} call, and the failed form the summary re-prints after {@code run} has
 * returned; rove's internal {@code ROVE/CORE/ASSERTION::OUTPUT-OF} stays qualified
 * everywhere -- so no text-level divergence remains. The exercise sets
 * {@code *print-pretty*} to nil so SBCL's line-wrapping of long {@code ~W} forms does not
 * manufacture a fourth difference.
 */
class RoveE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "rove-demo")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :rove)
			(setf rove:*enable-colors* nil)
			(setf *print-pretty* nil)
			(asdf:load-system :my-app/tests)
			(format t "~&my-app passed: ~A~%" (if (rove:run :my-app/tests) "yes" "no"))
			(asdf:load-system :my-plain/tests)
			(format t "~&my-plain passed: ~A~%" (if (rove:run :my-plain/tests) "yes" "no"))
			(format t "~&single passed: ~A~%" (if (rove:run-test 'my-app/tests/main::add-test) "yes" "no"))
			(in-package #:my-app/tests/main)
			(format t "~&suite passed: ~A~%" (if (rove:run-suite *package*) "yes" "no"))
			""";

	private static final List<String> EXPECTED = """
			Testing System my-app/tests

			;; testing 'my-app/tests/main'
			setup: my-app tests
			before: a test starts
			add-test
			adding two integers
			✓ Expect (= (ADD 1 2) 3) to be true.
			✓ Expect (= (ADD 1 2) 4) to be false.
			printing
			✓ Expect (EQUAL (ROVE/CORE/ASSERTION::OUTPUT-OF (WRITE-STRING "hi") *STANDARD-OUTPUT*) "hi") to be true.
			before: a test starts
			parse-token-test
			invalid tokens
			✓ Parse error
			✓ Expect (PARSE-TOKEN 10) to signal TYPE-ERROR.
			valid tokens
			✓ Expect (EQUAL (PARSE-TOKEN "a") "a") to be true.
			- unicode tokens are not supported yet
			before: a test starts
			misc-test
			✓ Okay. It's passed
			× 0) Oops. It's failed
			not implemented yet
			× 1) Expect (= (ADD 2 2) 5) to be true.
			× 1) Expect (PARSE-TOKEN "") to be true.
			teardown: my-app tests

			× 1 of 1 test failed

			0) Oops. It's failed

			1) my-app/tests/main
			› MISC-TEST
			Expect (PARSE-TOKEN "") to be true.
			APP-ERROR: Invalid token
			(MY-APP/MAIN:PARSE-TOKEN "")

			Summary:
			1 test failed.
			- my-app/tests/main
			my-app passed: no

			Testing System my-plain/tests

			;; testing 'my-plain/tests'
			greet-test
			formats the name
			✓ Expect (EQUAL (GREET "World") "Hello, World!") to be true.
			✓ Expect (EQUAL (GREET "World") "Hello!") to be false.

			✓ 1 test completed

			Summary:
			All 1 test passed.
			my-plain passed: yes
			add-test
			adding two integers
			✓ Expect (= (MY-APP/MAIN:ADD 1 2) 3) to be true.
			✓ Expect (= (MY-APP/MAIN:ADD 1 2) 4) to be false.
			printing
			✓ Expect (EQUAL (ROVE/CORE/ASSERTION::OUTPUT-OF (WRITE-STRING "hi") *STANDARD-OUTPUT*) "hi") to be true.
			single passed: yes

			;; testing 'my-app/tests/main'
			setup: my-app tests
			before: a test starts
			add-test
			adding two integers
			✓ Expect (= (ADD 1 2) 3) to be true.
			✓ Expect (= (ADD 1 2) 4) to be false.
			printing
			✓ Expect (EQUAL (ROVE/CORE/ASSERTION::OUTPUT-OF (WRITE-STRING "hi") *STANDARD-OUTPUT*) "hi") to be true.
			before: a test starts
			parse-token-test
			invalid tokens
			✓ Parse error
			✓ Expect (PARSE-TOKEN 10) to signal TYPE-ERROR.
			valid tokens
			✓ Expect (EQUAL (PARSE-TOKEN "a") "a") to be true.
			- unicode tokens are not supported yet
			before: a test starts
			misc-test
			✓ Okay. It's passed
			× 0) Oops. It's failed
			not implemented yet
			× 1) Expect (= (ADD 2 2) 5) to be true.
			× 1) Expect (PARSE-TOKEN "") to be true.
			teardown: my-app tests

			× 1 of 3 tests failed

			0) Oops. It's failed

			1) MISC-TEST
			Expect (PARSE-TOKEN "") to be true.
			APP-ERROR: Invalid token
			(PARSE-TOKEN "")

			Summary:
			1 test failed.
			- MISC-TEST
			suite passed: no
			""".lines().map(String::trim).toList();

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(Path.of("src", "test", "resources", "rove").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "dissect").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "cl-ppcre").toAbsolutePath().toString());
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String normalizeLine(String line) {
		return line.replaceFirst(" \\(\\d+ms\\)$", "");
	}

	@Override
	protected String artifactName() {
		return "RoveDemo";
	}

}

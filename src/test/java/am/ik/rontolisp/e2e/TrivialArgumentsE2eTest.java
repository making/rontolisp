package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * trivial-arguments 1.1.0 (Quicklisp 20260101, vendored unmodified under
 * {@code src/test/resources/trivial-arguments}, zlib) on all four backends via
 * {@link AsdfLibraryE2eSupport}. Its arms are the derived forms
 * {@code eval/TrivialArgumentsForms} ({@code .kb/asdf.md}, tier 4), whose markers this
 * pins against the release. A function's lambda list is the interpreter's: a compiled
 * function keeps none and answers {@code :unknown} ({@code ta-check}), while a macro
 * asking at expansion time is answered by the compile-time evaluator on every backend.
 * Every lambda list is SBCL 2.2.9's.
 */
class TrivialArgumentsE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "trivial-arguments")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :trivial-arguments)
			(defun ta-foo (a &optional (b 2) &key c) (list a b c))
			(defgeneric ta-bar (x y &key z))
			(defmethod ta-bar ((x integer) (y string) &key z) z)
			(defmethod ta-baz ((x integer) &optional (o 1) p &key (y 2) ((:kk k) 3 kp) &aux (w 1))
			  (list x o p y k kp w))
			(defmacro ta-mac (a (b c) &body body) (list* a b c body))
			(defmacro ta-arglist-at-expansion (name) `',(arg:arglist name))
			(print (ta-arglist-at-expansion ta-foo))
			(defun ta-check (designator interpreted)
			  (equal (arg:arglist designator) #+rontolisp-interpreter interpreted #-rontolisp-interpreter :unknown))
			(print (list (ta-check #'ta-foo '(a &optional (b 2) &key c)) (ta-check 'ta-bar '(x y &key z))
			             (ta-check #'ta-baz '(x &optional (o) p &key y ((:kk k))))
			             (ta-check 'ta-mac '(a (b c) &body body)) (ta-check 'car '(list))
			             (ta-check (lambda (x &rest r) (list x r)) '(x &rest r))))
			(let ((method (first (compute-applicable-methods #'ta-bar (list 1 "s")))))
			  (print (list (arg:arglist method) (arg:argtypes method))))
			""";

	private static final List<String> EXPECTED = List.of("(A &OPTIONAL (B 2) &KEY C)", "(T T T T T T)",
			"((X Y &KEY Z) (INTEGER STRING))");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
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
	protected String artifactName() {
		return "TrivialArgumentsE2e";
	}

}

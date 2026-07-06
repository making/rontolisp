package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code .todo/65} integration target: the REAL cl-utilities v1.2.4 sources (vendored
 * unmodified under {@code src/test/resources/cl-utilities}, public domain) load via
 * {@code asdf:load-system} and their whole public API works -- its own split-sequence
 * (which goes through {@code apply #'position} with runtime keywords), the extremum
 * family (nested-backquote {@code once-only}/{@code with-check-length} templates
 * referencing package-internal helpers), {@code read-delimited} ({@code read-char} +
 * {@code multiple-value-setq} + {@code (setf (elt ...))}), {@code expt-mod}, the
 * tail-collecting {@code collecting}/{@code with-collectors},
 * {@code with-unique-names}/{@code with-gensyms}/{@code once-only} used from user macros,
 * {@code rotate-byte} ({@code return-from}), the verbatim {@code copy-array}
 * ({@code apply #'make-array}) and {@code compose} ({@code reduce #'funcall
 * :from-end}). Runs on all four backends via {@link AsdfLibraryE2eSupport}: the
 * interpreter path drives {@code LispEvaluator} directly; the compile paths mirror the
 * CLI pipeline ({@code LoadInliner} splices the system, {@code UserMacroExpander} expands
 * its defmacros) into the JVM/WASM compilers.
 */
class ClUtilitiesE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-utilities")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-utilities)
			(print (cl-utilities:split-sequence #\\, "a,b,,c"))
			(print (cl-utilities:split-sequence #\\, "a,b,,c" :remove-empty-subseqs t))
			(print (cl-utilities:split-sequence-if #'evenp '(1 2 3 4 5)))
			(print (cl-utilities:split-sequence-if-not #'oddp '(1 2 3 4 5)))
			(print (cl-utilities:extremum '(3 1 4 1 5 9 2 6) #'<))
			(print (cl-utilities:extremum '(3 1 4 1 5 9 2 6) #'>))
			(print (cl-utilities:extremum '((1 . "one") (3 . "three") (2 . "two")) #'> :key #'car))
			(print (cl-utilities:extremum '(9 8 3 1 2) #'< :start 2))
			(print (cl-utilities:extremum-fastkey '(3 1 4 1 5) #'< :key #'identity))
			(print (cl-utilities:extrema '(3 1 4 1 5 9 2 6 1) #'<))
			(print (cl-utilities:n-most-extreme 3 '(3 1 4 1 5 9 2 6) #'<))
			(print (with-input-from-string (s "hello,world")
			         (let ((buf (make-array 20 :initial-element nil)))
			           (multiple-value-bind (pos found) (cl-utilities:read-delimited buf s :delimiter #\\,)
			             (list pos found (subseq (coerce buf 'list) 0 pos))))))
			(print (cl-utilities:expt-mod 2 10 1000))
			(print (cl-utilities:expt-mod 12 34 235))
			(print (cl-utilities:collecting
			         (dotimes (x 5)
			           (cl-utilities:collect (* x x)))))
			(print (multiple-value-list
			        (cl-utilities:with-collectors (evens odds)
			          (dolist (n '(1 2 3 4 5 6))
			            (if (evenp n) (evens n) (odds n))))))
			(defmacro my-square (x)
			  (cl-utilities:once-only (x)
			    `(* ,x ,x)))
			(let ((counter 0))
			  (flet ((bump () (incf counter)))
			    (print (my-square (bump)))
			    (print counter)))
			(defmacro my-swap (a b)
			  (cl-utilities:with-unique-names (tmp)
			    `(let ((,tmp ,a))
			       (setq ,a ,b)
			       (setq ,b ,tmp)
			       (list ,a ,b))))
			(let ((p 1) (q 2))
			  (print (my-swap p q)))
			(defmacro my-double (x)
			  (cl-utilities:with-gensyms (g)
			    `(let ((,g ,x)) (+ ,g ,g))))
			(print (my-double 21))
			(print (cl-utilities:rotate-byte 3 (byte 8 0) 1))
			(print (cl-utilities:rotate-byte 2 (byte 8 0) 255))
			(print (cl-utilities:rotate-byte -1 (byte 4 0) 1))
			(let* ((a (vector 1 2 3))
			       (b (cl-utilities:copy-array a)))
			  (setf (aref b 0) 99)
			  (print (list (aref a 0) (aref b 0))))
			(print (funcall (cl-utilities:compose #'1+ #'1+) 40))
			(print (mapcar (cl-utilities:compose #'car #'cdr) '((1 2 3) (4 5 6))))
			""";

	private static final List<String> EXPECTED = List.of("(\"a\" \"b\" \"\" \"c\")", "(\"a\" \"b\" \"c\")",
			"((1) (3) (5))", "((1) (3) (5))", "1", "9", "(3 . \"three\")", "1", "1", "(1 1 1)", "(1 1 2)",
			"(5 t (#\\h #\\e #\\l #\\l #\\o))", "24", "49", "(0 1 4 9 16)", "((2 4 6) (1 3 5))", "1", "1", "(2 1)",
			"42", "8", "255", "8", "(1 99)", "42", "(2 5)");

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
		return "TestClUtilities";
	}

}

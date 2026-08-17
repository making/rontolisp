package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The MUSTACHE SPEC suite, run on all four backends over the vendored cl-mustache
 * ({@link ClMustacheE2eTest} covers the API surface).
 *
 * <p>
 * {@code t/test-spec.lisp} is machine-generated from {@code github.com/mustache/spec} --
 * 194 independent cases -- so it is an oracle we did not write, and it is loaded here
 * VERBATIM from the vendored sources. Upstream writes it against {@code prove}, which the
 * tree does not have; the twenty lines below stand in for the three entry points the
 * generated file uses ({@code plan} / {@code is} / {@code finalize}). The shim prints ONE
 * character per case rather than a report, so the expected line pins the exact pass/fail
 * SET, not merely the count: a backend that starts failing a different case is a
 * different string even at the same 158.
 *
 * <p>
 * 158/194 is PARITY WITH UPSTREAM, not a shortfall of ours: SBCL 2.x fails
 * byte-identically the same 36 cases through the same shim. Nulls do not interpolate as
 * the empty string, dotted names push a context frame, and the whole
 * {@code ~inheritance.json} module (26 cases, the trailing run of {@code F}s) is
 * unimplemented -- cl-mustache targets spec 1.1.2, which predates it. Implementing them
 * is a change to cl-mustache, not to rontolisp, so this test is a REGRESSION pin: if a
 * number here moves, a rontolisp change moved it.
 */
class ClMustacheSpecE2eTest extends AsdfLibraryE2eSupport {

	private static final Path SYSTEM_DIR = Path.of("src", "test", "resources", "cl-mustache").toAbsolutePath();

	private static final String EXERCISE = """
			(defpackage #:prove (:use #:cl) (:export #:plan #:is #:finalize))
			(in-package #:prove)
			(defvar *map* nil)
			(defvar *pass* 0)
			(defvar *fail* 0)
			(defvar *n* 0)
			(defun plan (n) (setf *map* nil *pass* 0 *fail* 0 *n* n) t)
			(defmacro is (got expected &optional desc)
			  (declare (ignore desc))
			  `(if (equalp (handler-case ,got (error () :error)) ,expected)
			       (progn (incf *pass*) (push #\\. *map*))
			       (progn (incf *fail*) (push #\\F *map*))))
			(defun finalize ()
			  (format t "~A~%" (coerce (reverse *map*) 'string))
			  (format t "planned=~A pass=~A fail=~A~%" *n* *pass* *fail*))
			(in-package #:cl-user)
			(asdf:load-system :cl-mustache)
			""" + "(load \"" + SYSTEM_DIR.resolve(Path.of("t", "test-spec.lisp")) + "\")\n";

	// One character per spec case, in file order: comments, delimiters, interpolation,
	// inverted, partials, sections, ~dynamic-names, ~inheritance (the final 26).
	private static final List<String> EXPECTED = List.of(
			"......................................FFF..................................................................F.F"
					+ "..................................FFF.........FFFFFFFFFFFFFFFFFFFFFFFFFFFF..........",
			"planned=194 pass=158 fail=36");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR.toString();
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
		return "TestClMustacheSpec";
	}

}

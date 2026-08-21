package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL trivia sources
 * (vendored unmodified under {@code src/test/resources/trivia}, LLGPL; quicklisp dist
 * trivia-20260101-git) load via {@code asdf:load-system} and the {@code match} /
 * {@code ematch} / {@code guard} / {@code defpattern} surface runs on ALL FOUR backends
 * via {@link AsdfLibraryE2eSupport}. System {@code trivia} is mapped to the
 * {@code trivia.trivial} contents (the {@code :trivial} optimizer route) by the
 * {@code AsdOverrides} replacement {@code trivia-trivial.asd} -- upstream's own
 * sanctioned base system; the balland2006 optimizer needs iterate + type-i and buys only
 * optimization, zero semantics (divergence record: {@code .kb/asdf.md}). trivia is THE
 * gate for both sxql ({@code match} throughout) and mito.
 *
 * <p>
 * The smoke matrix is: constant / variable / cons / list* / vector patterns,
 * {@code guard}, {@code or}/{@code and} patterns, {@code ematch} failure signaling
 * {@code match-error}, {@code defpattern}, a struct pattern (keyword AND conc-name
 * shapes), a class pattern (keyword and {@code (class name (slot var))} shapes), and the
 * shapes sxql/mito actually use -- sxql's {@code (list* (type keyword) _)} (sxql.lisp)
 * and mito's cons/guard/eql nest (dao.lisp's expand-op).
 *
 * <p>
 * Substrate the load exercises, each landed for this milestone: the reader's
 * {@code ,@ . ,tail} backquote shape (level2 impl.lisp's inline-pattern-expand),
 * {@code symbol-macrolet} (level1/level2 expansions), lisp-namespace (via the
 * {@code (setf (macro-function ...))} alias), the {@code trivial-cltl2} shim, the
 * closer-mop shim's {@code compute-slots}, the empty
 * {@code generic-function}/{@code bit-vector}/{@code structure-class} types, the empty
 * {@code &key} lambda-list section (the {@code :trivial} optimizer's
 * {@code (clauses &key &allow-other-keys)}), {@code use-value}, the
 * signal-falls-through-unmatched-handler-case rule, {@code remove-if-not :key},
 * {@code find-symbol}'s definition-is-an-interning probe (+ the {@code #'find-symbol}
 * wrapper), the runtime {@code export} lowering, and the compile path's loaded-dependency
 * replay (spliced-system top-level forms run in the macro-time evaluator -- trivia
 * registers its vector matchers with plain top-level calls).
 */
class TriviaE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "trivia").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system "trivia")
			(defpackage :trivia-e2e (:use :cl :trivia))
			(in-package :trivia-e2e)
			;; constant / variable / cons / list* / vector patterns
			(print (match 42 (42 :const-ok)))
			(print (match '(1 2 3) ((list a b c) (+ a b (* 10 c)))))
			(print (match '(1 . 2) ((cons a b) (list :cons a b))))
			(print (match '(1 2 3 4) ((list* a b rest) (list :list* a b rest))))
			(print (match #(1 2 3) ((vector a b c) (list :vec a b c))))
			;; guard / or / and
			(print (match 15 ((guard x (> x 10)) (list :guard x))))
			(print (match 5 ((or 3 5 7) :or-ok)))
			(print (match '(1 2) ((and (list a _) (list _ b)) (list :and a b))))
			;; ematch failure signals match-error
			(print (handler-case (ematch 99 (1 :no))
			         (error (e) (list :ematch-error (typep e 'trivia:match-error)))))
			;; defpattern
			(defpattern my-pair (a b) `(cons ,a ,b))
			(print (match '(x . y) ((my-pair p q) (list :defpattern p q))))
			;; struct patterns (keyword and conc-name shapes)
			(defstruct point x y)
			(print (match (make-point :x 1 :y 2) ((point :x px :y py) (list :struct px py))))
			(print (match (make-point :x 3 :y 4) ((point- (x px) (y py)) (list :struct2 px py))))
			;; class patterns (keyword and (class name (slot var)) shapes)
			(defclass door () ((width :initarg :w :accessor door-width)))
			(print (match (make-instance 'door :w 9) ((door :w w) (list :class w))))
			(print (ematch (make-instance 'door :w 7) ((class door (width w)) (list :class2 w))))
			;; the sxql shape (sxql.lisp): (list* (type keyword) _)
			(print (match '(:a 1 2) ((list* (type keyword) _) :sxql-kw)))
			;; the mito shape (dao.lisp expand-op): cons/guard/eql nest
			(print (match '(:= :name "Bob")
			         ((cons (guard op (eql op :=)) (cons x (cons y nil))) (list :mito op x y))))
			""";

	private static final List<String> EXPECTED = List.of(":CONST-OK", "33", "(:CONS 1 2)", "(:LIST* 1 2 (3 4))",
			"(:VEC 1 2 3)", "(:GUARD 15)", ":OR-OK", "(:AND 1 2)", "(:EMATCH-ERROR T)",
			"(:DEFPATTERN TRIVIA-E2E::X TRIVIA-E2E::Y)", "(:STRUCT 1 2)", "(:STRUCT2 3 4)", "(:CLASS 9)", "(:CLASS2 7)",
			":SXQL-KW", "(:MITO := :NAME \"Bob\")");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "lisp-namespace").toAbsolutePath().toString());
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
		return "TriviaDemo";
	}

}

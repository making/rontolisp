package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Evaluates one lowered core form in a macro-time environment: the false value, the
 * spliced {@code clojure.lisp} library, the per-program runtimes and the core builtins,
 * plus the program's top-level definitions lowered so far. A Clojure macro is a lowered
 * lambda over encoded argument values, so expanding a call site means evaluating one such
 * application; the implementation lives in {@code eval} (which owns the evaluator) and is
 * supplied by whoever drives the lowering -- a file read, a session buffer -- so this
 * package keeps seeing only the AST types and {@code reader}.
 */
public interface ClojureMacroEvaluator {

	/**
	 * Evaluates one core form in the macro-time environment, after every definition
	 * handed over so far.
	 * @param form the lowered form
	 * @return the value
	 */
	LispVal evaluate(LispVal form);

	/**
	 * Hands over one lowered top-level definition (a {@code defun}, a {@code declaim}, a
	 * method or protocol table store): it evaluates, in order, before the next
	 * {@link #evaluate}. One that fails to evaluate is dropped, so a definition only the
	 * run time can make never fails a compile no expansion of which reads it.
	 * @param form the lowered definition
	 */
	default void define(LispVal form) {
	}

	/**
	 * Hands over a var's root whose value form runs only when an expansion reads the var:
	 * a top-level {@code def} builds its value for the program, so a value no expansion
	 * reads -- a server started at load time -- is never built here.
	 * @param var the var's global
	 * @param value the lowered value form
	 * @param special whether the var is dynamic (proclaimed special)
	 * @param once whether a var already bound keeps its root ({@code defonce})
	 */
	default void defineLazy(LispSymbol var, LispVal value, boolean special, boolean once) {
	}

}

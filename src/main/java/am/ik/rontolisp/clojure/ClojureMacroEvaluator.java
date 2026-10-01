package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispVal;

/**
 * Evaluates one lowered core form in a macro-time environment: the false value, the
 * spliced {@code clojure.lisp} library and the core builtins. A Clojure macro is a
 * lowered lambda over encoded argument values, so expanding a call site means evaluating
 * one such application; the implementation lives in {@code eval} (which owns the
 * evaluator) and is supplied by whoever drives the lowering -- a file read, a session
 * buffer -- so this package keeps seeing only the AST types and {@code reader}.
 */
public interface ClojureMacroEvaluator {

	/**
	 * Evaluates one core form in the macro-time environment.
	 * @param form the lowered form
	 * @return the value
	 */
	LispVal evaluate(LispVal form);

}

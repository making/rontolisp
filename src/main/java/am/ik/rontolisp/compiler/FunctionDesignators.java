package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Static rewriting of function designators for the compilers. Common Lisp allows a symbol
 * as a function designator (e.g. {@code (funcall 'car x)}); the compilers support the
 * statically-known case by rewriting a literal {@code (quote name)} in function position
 * into {@code (function name)}, which both backends resolve against the compile-time
 * function registry.
 */
public final class FunctionDesignators {

	private FunctionDesignators() {
	}

	/**
	 * Rewrites a literal {@code (quote name)} function argument into
	 * {@code (function name)}; any other form is returned unchanged.
	 * @param fnForm the expression in function-designator position
	 * @return the normalized expression
	 */
	public static LispVal normalize(LispVal fnForm) {
		if (fnForm instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol sym && !sym.isKeyword()) {
			return new LispCons(new LispSymbol(LispNames.FUNCTION), new LispCons(sym, LispNil.INSTANCE));
		}
		return fnForm;
	}

}

package am.ik.rontolisp.compiler;

import org.jspecify.annotations.Nullable;

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

	/**
	 * The function NAME a designator the compiler can READ spells -- {@code #'name} or
	 * {@code 'name} -- or {@code null} for one it cannot (a variable, a call, an inline
	 * {@code (lambda ...)}).
	 *
	 * <p>
	 * This is what lets an operator that funcalls its function argument emit the DIRECT
	 * call its head-position spelling would have emitted instead of routing the value
	 * through the arity dispatcher; a name is only a candidate, and each backend still
	 * decides whether its own registry answers it at the arity in hand.
	 *
	 * <p>
	 * A local function shadowing the name is not a concern here: {@code flet}/{@code
	 * labels} rewrite both {@code (f x)} and {@code #'f} into their binding VARIABLE
	 * before any backend sees the form ({@code .kb/flet-labels.md}), so a surviving
	 * {@code (function name)} names the global one by construction.
	 * @param fnForm the expression in function-designator position
	 * @return the name, or {@code null} when the designator is not a literal one
	 */
	public static @Nullable String literalName(LispVal fnForm) {
		return normalize(fnForm) instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.FUNCTION.equals(op.name()) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol sym && !sym.isKeyword() && rest.cdr() instanceof LispNil
						? sym.name() : null;
	}

}

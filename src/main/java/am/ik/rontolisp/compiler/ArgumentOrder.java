package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The backend-shared test behind Common Lisp's <em>left-to-right</em> argument evaluation
 * order on the compile paths.
 *
 * <p>
 * Some emitters have to consume their operands in an order the language does not specify
 * -- {@code list} links its cons chain from the LAST element backwards, an array
 * initializer fills from the end -- and emitting the argument expressions in that
 * consumption order makes the side effects run right to left, which is observable (see
 * {@code .kb/argument-evaluation-order.md}). The fix is to pre-evaluate each argument
 * into a temp in source order and consume the temps; this class says which arguments do
 * not need the temp, so the common all-literal case emits exactly the bytes it used to.
 *
 * <p>
 * The predicate is deliberately conservative: only forms whose evaluation can neither
 * cause nor observe a side effect qualify. A bare variable reference does NOT -- an
 * earlier argument may {@code setq} it, and hoisting the read past the other arguments
 * would then read a value a LATER argument stored.
 */
public final class ArgumentOrder {

	private ArgumentOrder() {
	}

	/**
	 * Whether evaluating this argument form can be reordered against its siblings without
	 * any observable difference.
	 * @param form the argument form as it appears in the source
	 * @return {@code true} when the form is a constant whose evaluation has no effect
	 */
	public static boolean isOrderIndependent(LispVal form) {
		if (form instanceof LispCons cons) {
			// (quote DATUM) is a constant; every other cons is a call or a special form
			// and is assumed effectful.
			return cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name());
		}
		if (form instanceof LispSymbol sym) {
			// Self-evaluating symbols only: keywords, nil and t. Any other symbol is a
			// variable read (see the class comment).
			return sym.isKeyword() || "NIL".equals(sym.name()) || "T".equals(sym.name());
		}
		// Numbers, strings, characters, nil/t singletons, array literals: all constants.
		return true;
	}

}

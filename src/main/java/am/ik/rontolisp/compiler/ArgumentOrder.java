package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

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

	/**
	 * The operators whose value, when they return one, is a real number in a program that
	 * cannot observe a complex: the arithmetic, its rounding family and {@code float}.
	 */
	private static final Set<String> REAL_VALUED = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV,
			LispNames.ONE_PLUS, LispNames.ONE_MINUS, LispNames.MOD, LispNames.REM, LispNames.ABS, LispNames.MIN,
			LispNames.MAX, LispNames.FLOAT, LispNames.FLOOR, LispNames.CEILING, LispNames.TRUNCATE, LispNames.ROUND);

	/**
	 * The integer operators that cannot signal once every operand is an integer: their
	 * result is an integer again, whatever the magnitudes ({@code mod}, {@code rem} and
	 * {@code ash} are out -- a zero divisor and a huge count signal).
	 */
	private static final Set<String> INTEGER_CLOSED = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL,
			LispNames.ONE_PLUS, LispNames.ONE_MINUS, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR,
			LispNames.LOGNOT);

	/**
	 * Whether evaluating an operand form can be observed by anything evaluated after it:
	 * a side effect, a non-local exit or a condition. A constant cannot, and neither can
	 * a read of a variable that is always bound there -- a lexical one, or a global the
	 * backend knows to hold a value; any other form is assumed to. An operation whose
	 * later operands are all quiet can convert its operands early without anyone seeing
	 * the difference (`.kb/argument-evaluation-order.md`, "An operation applies after its
	 * operands").
	 * @param form the operand form
	 * @param quietVariable whether a read of the named variable can neither fail nor
	 * change anything where the form is compiled
	 * @return {@code true} when the form's evaluation cannot be observed
	 */
	public static boolean isQuiet(LispVal form, Predicate<String> quietVariable) {
		if (form instanceof LispSymbol sym && !isOrderIndependent(form)) {
			return quietVariable.test(sym.name());
		}
		return isOrderIndependent(form);
	}

	/**
	 * Whether a form's value, when it returns one, is a real number in a program that
	 * cannot observe a complex: a number literal, or a call to an arithmetic operator.
	 * Converting such a value to a float cannot signal, so an operation need not wait for
	 * its later operands before converting it.
	 * @param form the operand form
	 * @return {@code true} when the value is certainly real
	 */
	public static boolean isRealValued(LispVal form) {
		if (form instanceof LispInteger || form instanceof LispDouble || form instanceof LispRatio
				|| form instanceof LispBigInteger) {
			return true;
		}
		return form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& REAL_VALUED.contains(head.name());
	}

	/**
	 * The variables of an integer arithmetic form that cannot signal once each of them
	 * holds an integer: {@code + - * 1+ 1- logand logior logxor lognot} over integer
	 * literals, quiet variables ({@link #isQuiet}) and such forms. Its evaluation is then
	 * as quiet as a variable read, which is what a fused tree checks to move its pending
	 * operations past it.
	 * @param form the form
	 * @param quietVariable whether a read of the named variable can neither fail nor
	 * change anything where the form is compiled
	 * @return the variables in the order they are read, or {@code null} when the form is
	 * not that shape
	 */
	public static @Nullable List<String> integerArithmeticVariables(LispVal form, Predicate<String> quietVariable) {
		List<String> variables = new ArrayList<>();
		return collectIntegerArithmetic(form, quietVariable, variables) ? variables : null;
	}

	private static boolean collectIntegerArithmetic(LispVal form, Predicate<String> quietVariable, List<String> out) {
		if (form instanceof LispInteger) {
			return true;
		}
		if (form instanceof LispSymbol sym) {
			if (isOrderIndependent(form) || !quietVariable.test(sym.name())) {
				return false;
			}
			out.add(sym.name());
			return true;
		}
		if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
				|| !INTEGER_CLOSED.contains(head.name()) || !(cons.cdr() instanceof LispCons)) {
			return false;
		}
		LispVal operands = cons.cdr();
		while (operands instanceof LispCons cell) {
			if (!collectIntegerArithmetic(cell.car(), quietVariable, out)) {
				return false;
			}
			operands = cell.cdr();
		}
		return true;
	}

}

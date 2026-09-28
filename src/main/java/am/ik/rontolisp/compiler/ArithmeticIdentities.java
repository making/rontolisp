package am.ik.rontolisp.compiler;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

import org.jspecify.annotations.Nullable;

/**
 * What an arithmetic operator answers with NO arguments: {@code (+)} is 0 and {@code (*)}
 * is 1, the identities of their operations (CLHS 12.2). Both backends' arithmetic
 * compilers fold over "the first argument, then the rest" and so have no first argument
 * to start from; they ask here instead, which keeps the two agreeing with each other and
 * with the interpreter. Every other operator requires an argument, and is rejected with
 * the interpreter's count report ({@code - expects at least 1 argument, got 0}).
 *
 * <p>
 * With ONE argument the fold has nothing to apply, so {@code (+ x)} and {@code (* x)}
 * would answer {@code x} unexamined; {@link #oneArgument} answers it checked instead.
 */
public final class ArithmeticIdentities {

	private ArithmeticIdentities() {
	}

	/**
	 * The value of an arithmetic form that has no arguments.
	 * @param form the {@code (operator)} form
	 * @return the identity literal
	 * @throws IllegalArgumentException when the operator has no identity
	 */
	public static LispVal of(LispCons form) {
		String operator = form.car() instanceof LispSymbol symbol ? symbol.name() : form.car().print();
		return switch (operator) {
			case LispNames.ADD -> new LispInteger(0);
			case LispNames.MUL -> new LispInteger(1);
			default -> throw new IllegalArgumentException(ClosRegistry.arityMessage(operator, 1, true, 0));
		};
	}

	/**
	 * The value of a one-argument {@code (+ x)} or {@code (* x)}: {@code x}, checked to
	 * be a number ({@link LispMacroExpander#checkedOneArgument}), so a non-number is the
	 * operator's type-error as the interpreter's built-in signals it. {@code (- x)} and
	 * {@code (/ x)} compute, and check through the computation.
	 * @param form the one-argument form
	 * @return the checked form, or null for an operator whose one argument computes
	 */
	public static @Nullable LispVal oneArgument(LispCons form) {
		if (!(form.car() instanceof LispSymbol symbol) || !(form.cdr() instanceof LispCons args)) {
			return null;
		}
		String operator = symbol.name();
		if (!LispNames.ADD.equals(operator) && !LispNames.MUL.equals(operator)) {
			return null;
		}
		return LispMacroExpander.checkedOneArgument(operator, args.car(), null);
	}

}

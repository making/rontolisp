package am.ik.rontolisp.compiler;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * What an arithmetic operator answers with NO arguments: {@code (+)} is 0 and {@code (*)}
 * is 1, the identities of their operations (CLHS 12.2). Both backends' arithmetic
 * compilers fold over "the first argument, then the rest" and so have no first argument
 * to start from; they ask here instead, which keeps the two agreeing with each other and
 * with the interpreter. Every other operator requires an argument, and is rejected with
 * the interpreter's count report ({@code - expects at least 1 argument, got 0}).
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

}

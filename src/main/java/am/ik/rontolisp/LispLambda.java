package am.ik.rontolisp;

import java.util.List;

/**
 * A lambda (closure) value.
 *
 * @param params the parameter symbols
 * @param body the body expressions
 * @param closure the captured lexical scope
 */
public record LispLambda(List<LispSymbol> params, List<LispVal> body, Scope closure) implements LispVal {

	@Override
	public String print() {
		return "#<lambda>";
	}

}

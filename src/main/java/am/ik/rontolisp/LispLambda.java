package am.ik.rontolisp;

import java.util.List;

public record LispLambda(List<LispSymbol> params, List<LispVal> body, Scope closure) implements LispVal {

	@Override
	public String print() {
		return "#<lambda>";
	}

}

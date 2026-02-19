package am.ik.femtolisp;

import java.util.List;
import java.util.function.Function;

public record LispFunction(String name, Function<List<LispVal>, LispVal> body) implements LispVal {

	@Override
	public String print() {
		return "#<function " + this.name + ">";
	}

}

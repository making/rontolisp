package am.ik.rontolisp;

import java.util.List;
import java.util.function.Function;

/**
 * A built-in function value.
 *
 * @param name the function name
 * @param body the function implementation
 * @param passesValues whether the function's own answer may be other than exactly one
 * value -- it publishes extra values itself ({@code values}, {@code parse-integer}) or it
 * hands the values of code it runs on ({@code funcall}, {@code apply}, {@code eval}). The
 * interpreter clears the multiple-value channel after every other built-in returns,
 * because a callback the built-in ran may have published in between and the built-in's
 * answer is one value regardless (see {@code LispEvaluator.apply}).
 */
public record LispFunction(String name, Function<List<LispVal>, LispVal> body,
		boolean passesValues) implements LispVal {

	/**
	 * A built-in that answers exactly one value.
	 * @param name the function name
	 * @param body the function implementation
	 */
	public LispFunction(String name, Function<List<LispVal>, LispVal> body) {
		this(name, body, false);
	}

	@Override
	public String print() {
		return "#<function " + this.name + ">";
	}

}

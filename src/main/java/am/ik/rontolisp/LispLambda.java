package am.ik.rontolisp;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A lambda (closure) value. Extended lambda lists ({@code &optional}, {@code &key},
 * {@code &aux}) are desugared by {@link LambdaLists} before construction, so only the
 * native shape remains: required parameter symbols plus an optional {@code &rest}
 * parameter collecting the remaining arguments as a list.
 *
 * @param params the required parameter symbols
 * @param rest the {@code &rest} parameter, or {@code null} for a fixed-arity lambda
 * @param body the body expressions
 * @param closure the captured lexical scope
 * @param name the function name a {@code defun} installed this lambda under, or
 * {@code null} for an anonymous one. Print metadata only: a named lambda prints
 * {@code #<function NAME>} -- the text {@code LispFunction.print()} answers for a
 * built-in and both compiled backends emit from their function-name table -- while an
 * anonymous one keeps {@code #<lambda>}
 * @param sourced whether this is a named function whose body holds a form read from a
 * named file -- the program's own code, which the uncaught-condition report may name as
 * the function a condition happened in. A function a library defines is not: a report
 * names the program's function that called it, the way every compiled backend's does
 */
public record LispLambda(List<LispSymbol> params, @Nullable LispSymbol rest, List<LispVal> body, Scope closure,
		@Nullable String name, boolean sourced) implements LispVal {

	/**
	 * Creates an anonymous fixed-arity lambda (no {@code &rest} parameter, no name).
	 * @param params the required parameter symbols
	 * @param body the body expressions
	 * @param closure the captured lexical scope
	 */
	public LispLambda(List<LispSymbol> params, List<LispVal> body, Scope closure) {
		this(params, null, body, closure, null, false);
	}

	/**
	 * Creates an anonymous lambda (no name).
	 * @param params the required parameter symbols
	 * @param rest the {@code &rest} parameter, or {@code null} for a fixed-arity lambda
	 * @param body the body expressions
	 * @param closure the captured lexical scope
	 */
	public LispLambda(List<LispSymbol> params, @Nullable LispSymbol rest, List<LispVal> body, Scope closure) {
		this(params, rest, body, closure, null, false);
	}

	@Override
	public String print() {
		return this.name == null ? "#<lambda>" : "#<function " + this.name + ">";
	}

}

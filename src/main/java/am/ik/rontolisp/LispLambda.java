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
 */
public record LispLambda(List<LispSymbol> params, @Nullable LispSymbol rest, List<LispVal> body,
		Scope closure) implements LispVal {

	/**
	 * Creates a fixed-arity lambda (no {@code &rest} parameter).
	 * @param params the required parameter symbols
	 * @param body the body expressions
	 * @param closure the captured lexical scope
	 */
	public LispLambda(List<LispSymbol> params, List<LispVal> body, Scope closure) {
		this(params, null, body, closure);
	}

	@Override
	public String print() {
		return "#<lambda>";
	}

}

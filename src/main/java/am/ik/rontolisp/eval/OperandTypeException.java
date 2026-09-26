package am.ik.rontolisp.eval;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;

import org.jspecify.annotations.Nullable;

/**
 * A wrong-type operand a coercion funnel ({@code Environment.asLong} and its siblings)
 * rejected: a {@code type-error} carrying the operand and the expected type. The funnel
 * does not know which operator it is serving, so it throws UNNAMED; the built-in seam in
 * {@code LispEvaluator.apply} names it after the built-in whose body raised it
 * ({@link #named}) when that built-in is one of {@link OperandTypes}' operators.
 */
final class OperandTypeException extends LispEvalException {

	private final transient LispVal datum;

	private final OperandTypes.Kind kind;

	private final @Nullable String operator;

	private OperandTypeException(LispVal datum, OperandTypes.Kind kind, @Nullable String operator) {
		super(OperandTypes.message(operator, datum.print(), OperandTypes.expectedType(operator, kind)), null,
				ClosRegistry.TYPE_ERROR_CLASS_NAME);
		this.datum = datum;
		this.kind = kind;
		this.operator = operator;
	}

	/**
	 * The unnamed error a funnel throws.
	 * @param datum the rejected operand
	 * @param kind what the funnel was coercing to
	 * @return the exception to throw
	 */
	static OperandTypeException of(LispVal datum, OperandTypes.Kind kind) {
		return new OperandTypeException(datum, kind, null);
	}

	/**
	 * This error attributed to the built-in whose body raised it, or itself when it is
	 * already named or the built-in is not a named operator.
	 * @param builtin the built-in's name
	 * @return the exception to throw on
	 */
	OperandTypeException named(String builtin) {
		String reported = OperandTypes.reportedOperator(builtin);
		if (this.operator != null || reported == null) {
			return this;
		}
		OperandTypeException named = new OperandTypeException(this.datum, this.kind, reported);
		named.setStackTrace(getStackTrace());
		return named;
	}

	/**
	 * The rejected operand, for the condition's {@code datum} slot.
	 * @return the operand
	 */
	LispVal datum() {
		return this.datum;
	}

	/**
	 * The type the report names, for the condition's {@code expected-type} slot.
	 * @return the type name
	 */
	String expectedType() {
		return OperandTypes.expectedType(this.operator, this.kind);
	}

}

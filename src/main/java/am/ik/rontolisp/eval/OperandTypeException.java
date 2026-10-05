package am.ik.rontolisp.eval;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
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

	/**
	 * The expected type when it is compound (an out-of-range subscript's
	 * {@code (INTEGER 0 (dim))}, the fill-pointer surface's), or null for a wrong-type
	 * operand, whose type comes from the kind and the operator.
	 */
	private final @Nullable LispVal compoundType;

	private OperandTypeException(LispVal datum, OperandTypes.Kind kind, @Nullable String operator,
			@Nullable LispVal compoundType) {
		this(OperandTypes.message(operator, datum.print(),
				compoundType == null ? OperandTypes.expectedType(operator, kind) : compoundType.print()), datum, kind,
				operator, compoundType);
	}

	private OperandTypeException(String report, LispVal datum, OperandTypes.Kind kind, @Nullable String operator,
			@Nullable LispVal compoundType) {
		super(report, null, ClosRegistry.TYPE_ERROR_CLASS_NAME);
		this.datum = datum;
		this.kind = kind;
		this.operator = operator;
		this.compoundType = compoundType;
	}

	/**
	 * The unnamed error a funnel throws.
	 * @param datum the rejected operand
	 * @param kind what the funnel was coercing to
	 * @return the exception to throw
	 */
	static OperandTypeException of(LispVal datum, OperandTypes.Kind kind) {
		return new OperandTypeException(datum, kind, null, null);
	}

	/**
	 * The unnamed error of an array subscript outside its dimension: the subscript is not
	 * of type {@code (INTEGER 0 (dimension))} ({@link OperandTypes#indexType}), CL's
	 * report for an out-of-range index. The access's seam names it like a wrong-type
	 * subscript.
	 * @param subscript the subscript as the program passed it
	 * @param dimension the dimension it indexes
	 * @return the exception to throw
	 */
	static OperandTypeException outOfRange(LispVal subscript, long dimension) {
		return new OperandTypeException(subscript, OperandTypes.Kind.INTEGER, null, indexType(dimension));
	}

	/**
	 * {@link #outOfRange(LispVal, long)} for an access that knows which operator it is
	 * serving.
	 * @param subscript the subscript as the program passed it
	 * @param dimension the dimension it indexes
	 * @param operator the operator's symbol name
	 * @return the exception to throw
	 */
	static OperandTypeException outOfRange(LispVal subscript, long dimension, String operator) {
		String reported = OperandTypes.reportedOperator(operator);
		return new OperandTypeException(subscript, OperandTypes.Kind.INTEGER, reported != null ? reported : operator,
				indexType(dimension));
	}

	/**
	 * The unnamed error of an operand that is not of a compound type
	 * ({@link OperandTypes#FILL_POINTER_VECTOR_TYPE},
	 * {@link OperandTypes#fillPointerType}): the seam names it, keeping the type.
	 * @param datum the rejected operand
	 * @param type the type, as nested lists of symbol names and numbers
	 * @return the exception to throw
	 */
	static OperandTypeException notOfType(LispVal datum, java.util.List<?> type) {
		return new OperandTypeException(datum, OperandTypes.Kind.ARRAY, null, typeValue(type));
	}

	/**
	 * A type error whose report the built-in words itself rather than as
	 * {@code OP: The value X is not of type T} -- {@code subseq}'s bounds report, kept
	 * byte-identical across the backends -- carrying its datum and compound type. It is
	 * already named, so the built-in seam leaves it as it is.
	 * @param report the full report
	 * @param operator the operator's symbol name
	 * @param datum the rejected value
	 * @param type the type, as nested lists of symbol names and numbers
	 * @return the exception to throw
	 */
	static OperandTypeException reported(String report, String operator, LispVal datum, java.util.List<?> type) {
		return new OperandTypeException(report, datum, OperandTypes.Kind.INTEGER, operator, typeValue(type));
	}

	private static LispVal indexType(long dimension) {
		return typeValue(java.util.List.of(OperandTypes.Kind.INTEGER.name(), 0L, java.util.List.of(dimension)));
	}

	// The Lisp value of a type spelled as nested lists of symbol names and numbers.
	private static LispVal typeValue(Object type) {
		if (type instanceof java.util.List<?> list) {
			LispVal value = LispNil.INSTANCE;
			for (int i = list.size() - 1; i >= 0; i--) {
				value = new LispCons(typeValue(list.get(i)), value);
			}
			return value;
		}
		if (type instanceof Long n) {
			return new LispInteger(n);
		}
		return new LispSymbol((String) type);
	}

	/**
	 * The error of a built-in that knows which operator it is serving: a walk that
	 * reports the step that failed ({@code nth}'s cdrs as {@code NTHCDR}, its last read
	 * as {@code CAR}), or a helper reached outside the built-in seam
	 * ({@code %schar-set}).
	 * @param datum the rejected operand
	 * @param kind what was checked for
	 * @param operator the operator's symbol name (a rewritten one reports as the operator
	 * it becomes)
	 * @return the exception to throw
	 */
	static OperandTypeException of(LispVal datum, OperandTypes.Kind kind, String operator) {
		String reported = OperandTypes.reportedOperator(operator);
		return new OperandTypeException(datum, kind, reported != null ? reported : operator, null);
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
		OperandTypeException named = new OperandTypeException(this.datum, this.kind, reported, this.compoundType);
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
	 * The type the report names, for the condition's {@code expected-type} slot: a type
	 * symbol, or a compound type's list ({@code (INTEGER 0 (dim))} of an out-of-range
	 * subscript).
	 * @return the type
	 */
	LispVal expectedType() {
		if (this.compoundType == null) {
			return new LispSymbol(OperandTypes.expectedType(this.operator, this.kind));
		}
		return this.compoundType;
	}

}

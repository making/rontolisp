package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The text and the expected type of a wrong-type operand reaching a numeric operator:
 * {@code >: The value NIL is not of type REAL}, the shape of a CL {@code type-error}
 * report. Every backend detects the failure at a coercion FUNNEL that knows only what it
 * was coercing to ({@link Kind}); the OPERATOR is attached one level up -- the
 * interpreter's built-in seam, the JVM's per-operator helper wrappers, the wasm runtime's
 * operator register -- and names the type that operator accepts ({@link #expectedType}).
 * One table, so the four backends cannot disagree on a name or a type
 * ({@code .kb/error-handling.md}, "A non-number reaching arithmetic").
 */
public final class OperandTypes {

	/** What a coercion funnel was converting to when the operand did not fit. */
	public enum Kind {

		/**
		 * An exact-integer coercion (the interpreter's {@code asLong}, JVM {@code _big},
		 * wasm {@code _int_val}).
		 */
		INTEGER,

		/**
		 * A to-double coercion (the interpreter's {@code asDouble}, JVM {@code _dbl},
		 * wasm {@code _as_f64}).
		 */
		NUMBER,

		/** A complex reaching an ordering or real-only operation. */
		REAL

	}

	/** The report's text before the printed operand. */
	public static final String VALUE_PREFIX = "The value ";

	/** The report's text between the printed operand and the type name. */
	public static final String TYPE_INFIX = " is not of type ";

	/** What follows the operator name in a named report. */
	public static final String OPERATOR_SEPARATOR = ": ";

	private static final Map<String, String> OPERATOR_TYPES = new HashMap<>();

	/** The operators, in a fixed order: a compiled backend numbers them by position. */
	private static final List<String> OPERATORS;

	/**
	 * Operators the shared expander rewrites in call position ({@code (1+ x)} is
	 * {@code (+ x 1)}, {@code (zerop x)} is {@code (= x 0)}, {@code (evenp x)} goes
	 * through {@code mod}, {@code logtest} through {@code logand}), reported under the
	 * operator they become, so a function value ({@code (mapcar #'1+ ...)}) reports what
	 * a call does -- the compiled backends' function value IS that rewrite
	 * ({@code BuiltinFunctionWrappers}).
	 */
	private static final Map<String, String> REWRITTEN = Map.of("1+", "+", "1-", "-", "/=", "=", "ZEROP", "=", "PLUSP",
			">", "MINUSP", "<", "EVENP", "MOD", "ODDP", "MOD", "LOGTEST", "LOGAND", "LOGEQV", "LOGXOR");

	static {
		String[] numberOps = { "+", "-", "*", "/", "=", "ABS", "SIGNUM", "SQRT", "EXP", "LOG", "EXPT", "SIN", "COS",
				"TAN", "ASIN", "ACOS", "ATAN", "SINH", "COSH", "TANH", "ASINH", "ACOSH", "ATANH", "CONJUGATE", "PHASE",
				"REALPART", "IMAGPART" };
		String[] realOps = { "<", ">", "<=", ">=", "MIN", "MAX", "FLOOR", "CEILING", "TRUNCATE", "ROUND", "FFLOOR",
				"FCEILING", "FTRUNCATE", "FROUND", "MOD", "REM", "FLOAT", "RATIONAL", "RATIONALIZE", "CIS" };
		String[] integerOps = { "LOGAND", "LOGIOR", "LOGXOR", "LOGEQV", "LOGNAND", "LOGNOR", "LOGANDC1", "LOGANDC2",
				"LOGORC1", "LOGORC2", "LOGNOT", "LOGCOUNT", "LOGBITP", "LOGTEST", "ASH", "INTEGER-LENGTH", "GCD", "LCM",
				"ISQRT" };
		List<String> order = new java.util.ArrayList<>();
		for (String op : numberOps) {
			OPERATOR_TYPES.put(op, Kind.NUMBER.name());
			order.add(op);
		}
		for (String op : realOps) {
			OPERATOR_TYPES.put(op, Kind.REAL.name());
			order.add(op);
		}
		for (String op : integerOps) {
			OPERATOR_TYPES.put(op, Kind.INTEGER.name());
			order.add(op);
		}
		OPERATORS = List.copyOf(order);
	}

	private OperandTypes() {
	}

	/**
	 * The name a wrong-type operand's report gives this operator: itself, the operator a
	 * call-position rewrite turns it into, or null when it is not a named operator.
	 * @param operator the operator's symbol name, or null
	 * @return the reported name, or null
	 */
	public static @Nullable String reportedOperator(@Nullable String operator) {
		if (operator == null) {
			return null;
		}
		String rewritten = REWRITTEN.get(operator);
		if (rewritten != null) {
			return rewritten;
		}
		return OPERATOR_TYPES.containsKey(operator) ? operator : null;
	}

	/**
	 * The call-position rewrites {@link #reportedOperator} reports under the operator
	 * they become, from rewritten name to reported name.
	 * @return the rewrites
	 */
	public static Map<String, String> rewritten() {
		return REWRITTEN;
	}

	/**
	 * The type a named operator accepts.
	 * @param operator the operator's symbol name
	 * @return {@code NUMBER}, {@code REAL} or {@code INTEGER}, or null for an operator
	 * that is not named
	 */
	public static @Nullable String operatorType(String operator) {
		return OPERATOR_TYPES.get(operator);
	}

	/**
	 * The named operators in their fixed order: position {@code i} is operator id
	 * {@code i + 1} on a backend that numbers them (id 0 meaning "no operator").
	 * @return the operator names
	 */
	public static List<String> operators() {
		return OPERATORS;
	}

	/**
	 * The type a report names: the one the operator accepts, narrowed to {@code REAL}
	 * when a {@code NUMBER} operator met a complex where only a real will do (the
	 * two-argument {@code atan}); the funnel's own kind when no operator is known.
	 * @param operator the operator, or null
	 * @param kind what the funnel was coercing to
	 * @return the type name
	 */
	public static String expectedType(@Nullable String operator, Kind kind) {
		String type = operator == null ? null : OPERATOR_TYPES.get(operator);
		if (type == null) {
			return kind.name();
		}
		if (kind == Kind.REAL && Kind.NUMBER.name().equals(type)) {
			return Kind.REAL.name();
		}
		return type;
	}

	/**
	 * The report text.
	 * @param operator the operator, or null for an unnamed report
	 * @param printedDatum the operand as {@code prin1} prints it
	 * @param expectedType the type name
	 * @return {@code OP: The value X is not of type T}, or without the {@code OP: }
	 */
	public static String message(@Nullable String operator, String printedDatum, String expectedType) {
		String body = VALUE_PREFIX + printedDatum + TYPE_INFIX + expectedType;
		return operator == null ? body : operator + OPERATOR_SEPARATOR + body;
	}

}

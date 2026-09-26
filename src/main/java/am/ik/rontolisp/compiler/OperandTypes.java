package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The text and the expected type of a wrong-type argument reaching a built-in:
 * {@code >: The value NIL is not of type REAL}, the shape of a CL {@code type-error}
 * report. Every backend detects the failure at a FUNNEL that knows only what it was
 * checking for ({@link Kind}); the OPERATOR is attached one level up -- the interpreter's
 * built-in seam, the JVM's per-operator helper wrappers, the wasm runtime's operator
 * register -- and names the type that operator requires ({@link #expectedType}). One
 * table, so the four backends cannot disagree on a name or a type
 * ({@code .kb/error-handling.md}, "A non-number reaching arithmetic" and "A wrong-type
 * argument names its operator").
 *
 * <p>
 * A numeric operator has ONE type: its funnels coerce to an integer or a double whatever
 * the operator accepts, so the operator's type replaces the funnel's. Every other named
 * operator is FUNNEL-TYPED: each of its funnels checks exactly one argument's type
 * ({@code aref}'s index is an {@code INTEGER}), so the funnel's kind is the type --
 * except that a to-double funnel ({@link Kind#NUMBER}) there is a packed float array's
 * store, which takes any real.
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
		REAL,

		/** A non-rational reaching {@code numerator}/{@code denominator}. */
		RATIONAL,

		/** A non-list reaching {@code car}/{@code cdr}. */
		LIST,

		/**
		 * The type of an operator that takes any sequence ({@code length},
		 * {@code reverse}, {@code nreverse}); no funnel checks for it, the operator's row
		 * names it.
		 */
		SEQUENCE,

		/**
		 * The type of an operator that takes a cons, never nil ({@code rplaca},
		 * {@code rplacd}): a funnel checking for it reports it unnamed, the operator's
		 * row names it.
		 */
		CONS,

		/** A non-string reaching {@code char}/{@code schar} or their {@code setf}. */
		STRING,

		/** A non-character stored into a string ({@code (setf char)} and its kin). */
		CHARACTER

	}

	/** The report's text before the printed operand. */
	public static final String VALUE_PREFIX = "The value ";

	/** The report's text between the printed operand and the type name. */
	public static final String TYPE_INFIX = " is not of type ";

	/** What follows the operator name in a named report. */
	public static final String OPERATOR_SEPARATOR = ": ";

	/** The reported name of a store through an {@code aref} place. */
	public static final String SETF_AREF = "(SETF AREF)";

	/** The reported name of a store through a {@code char} place. */
	public static final String SETF_CHAR = "(SETF CHAR)";

	/** The reported name of a store through a {@code schar} place. */
	public static final String SETF_SCHAR = "(SETF SCHAR)";

	/** The reported name of a store through a {@code row-major-aref} place. */
	public static final String SETF_ROW_MAJOR_AREF = "(SETF ROW-MAJOR-AREF)";

	/**
	 * The text of an out-of-range subscript's expected type before the dimension:
	 * {@code (INTEGER 0 (3))}, CL's {@code type-error} for an array index
	 * ({@link #indexType}).
	 */
	public static final String INDEX_TYPE_PREFIX = "(INTEGER 0 (";

	/** The text of an out-of-range subscript's expected type after the dimension. */
	public static final String INDEX_TYPE_SUFFIX = "))";

	/** An operator table entry naming a funnel-typed operator ({@link #operatorType}). */
	public static final String FUNNEL_TYPE = "";

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
	private static final Map<String, String> REWRITTEN = Map.ofEntries(Map.entry("1+", "+"), Map.entry("1-", "-"),
			Map.entry("/=", "="), Map.entry("ZEROP", "="), Map.entry("PLUSP", ">"), Map.entry("MINUSP", "<"),
			Map.entry("EVENP", "MOD"), Map.entry("ODDP", "MOD"), Map.entry("LOGTEST", "LOGAND"),
			Map.entry("LOGEQV", "LOGXOR"), Map.entry("FIRST", "CAR"), Map.entry("REST", "CDR"),
			Map.entry("NTH", "NTHCDR"), Map.entry("SVREF", "AREF"), Map.entry("%ASET", SETF_AREF),
			Map.entry("%ROW-MAJOR-ASET", SETF_ROW_MAJOR_AREF));

	/**
	 * The funnel-typed operators ({@link #expectedType}): {@code (setf aref)} is the
	 * reported name of {@code %aset}, the operator a {@code setf} of an {@code aref} or
	 * {@code svref} place lowers to. {@code endp} is also {@code dolist}'s and
	 * {@code loop}'s {@code for-in}: the expansions check the list's end as it does.
	 * {@code last}, the {@code map*} family, {@code append}, {@code list-length}, the
	 * {@code member}/{@code assoc}/{@code rassoc} scans and {@code copy-list} check their
	 * list arguments. A string access checks its string ({@code STRING}) and its
	 * subscript ({@code INTEGER}), a string store its value ({@code CHARACTER});
	 * {@code (setf row-major-aref)} is {@code %row-major-aset}'s reported name.
	 */
	private static final List<String> FUNNEL_TYPED = List.of("CAR", "CDR", "NTHCDR", "ENDP", "AREF", SETF_AREF, "CHAR",
			"SCHAR", "LAST", "MAPCAR", "MAPC", "MAPCAN", "MAPLIST", "MAPL", "MAPCON", SETF_CHAR, SETF_SCHAR, "APPEND",
			"LIST-LENGTH", "MEMBER", "MEMBER-IF", "ASSOC", "ASSOC-IF", "RASSOC", "RASSOC-IF", "ROW-MAJOR-AREF",
			SETF_ROW_MAJOR_AREF, "COPY-LIST");

	static {
		String[] numberOps = { "+", "-", "*", "/", "=", "ABS", "SIGNUM", "SQRT", "EXP", "LOG", "EXPT", "SIN", "COS",
				"TAN", "ASIN", "ACOS", "ATAN", "SINH", "COSH", "TANH", "ASINH", "ACOSH", "ATANH", "CONJUGATE", "PHASE",
				"REALPART", "IMAGPART" };
		String[] realOps = { "<", ">", "<=", ">=", "MIN", "MAX", "FLOOR", "CEILING", "TRUNCATE", "ROUND", "FFLOOR",
				"FCEILING", "FTRUNCATE", "FROUND", "MOD", "REM", "FLOAT", "RATIONAL", "RATIONALIZE", "CIS", "RANDOM",
				"COMPLEX" };
		String[] rationalOps = { "NUMERATOR", "DENOMINATOR" };
		// A list consumer whose one check is its own type: length and the reversals take
		// any sequence, rplaca/rplacd a cons (nil is no cons).
		String[][] fixedOps = { { "LENGTH", Kind.SEQUENCE.name() }, { "RPLACA", Kind.CONS.name() },
				{ "RPLACD", Kind.CONS.name() }, { "REVERSE", Kind.SEQUENCE.name() },
				{ "NREVERSE", Kind.SEQUENCE.name() } };
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
		for (String op : rationalOps) {
			OPERATOR_TYPES.put(op, Kind.RATIONAL.name());
			order.add(op);
		}
		for (String op : FUNNEL_TYPED) {
			OPERATOR_TYPES.put(op, FUNNEL_TYPE);
			order.add(op);
		}
		for (String[] op : fixedOps) {
			OPERATOR_TYPES.put(op[0], op[1]);
			order.add(op[0]);
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
	 * @return {@code NUMBER}, {@code REAL}, {@code INTEGER}, {@code RATIONAL},
	 * {@code SEQUENCE} or {@code CONS}, {@link #FUNNEL_TYPE} for a funnel-typed operator,
	 * or null for an operator that is not named
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
	 * two-argument {@code atan}); for a funnel-typed operator the funnel's kind, a
	 * to-double funnel's read as {@code REAL}; the funnel's own kind when no operator is
	 * known.
	 * @param operator the operator, or null
	 * @param kind what the funnel was checking for
	 * @return the type name
	 */
	public static String expectedType(@Nullable String operator, Kind kind) {
		String type = operator == null ? null : OPERATOR_TYPES.get(operator);
		if (type == null) {
			return kind.name();
		}
		if (FUNNEL_TYPE.equals(type)) {
			return (kind == Kind.NUMBER ? Kind.REAL : kind).name();
		}
		if (kind == Kind.REAL && Kind.NUMBER.name().equals(type)) {
			return Kind.REAL.name();
		}
		return type;
	}

	/**
	 * The type an array subscript outside its dimension is not of: {@code (INTEGER 0
	 * (dim))}, every integer in {@code [0, dim)} -- the expected type SBCL's
	 * {@code invalid-array-index-error} carries. The report names the operator's own type
	 * for no other failure: an out-of-range subscript is funnel-typed like a non-integer
	 * one, and its datum is the subscript.
	 * @param dimension the dimension the subscript indexes (the total size for a
	 * row-major access)
	 * @return the type's printed text
	 */
	public static String indexType(long dimension) {
		return INDEX_TYPE_PREFIX + dimension + INDEX_TYPE_SUFFIX;
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

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
		 * A non-sequence reaching a sequence operator: the row type of {@code length},
		 * {@code reverse} and {@code nreverse}, and the kind a funnel-typed sequence
		 * operator's check lands with ({@link #sequenceOperators}).
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

		/**
		 * A non-character stored into a string ({@code (setf char)} and its kin) or
		 * reaching a character built-in ({@link OperandTypes#characterOperators}).
		 */
		CHARACTER,

		/**
		 * A non-array reaching an array accessor ({@code aref}, {@code (setf aref)},
		 * {@code row-major-aref}, {@code array-dimensions} and its kin).
		 */
		ARRAY,

		/**
		 * A non-hash-table reaching a hash-table accessor ({@code gethash},
		 * {@code (setf gethash)}, {@code remhash}, {@code maphash},
		 * {@code hash-table-count}, {@code clrhash}).
		 */
		HASH_TABLE;

		/**
		 * The type's symbol name, as a report and an {@code expected-type} spell it:
		 * {@code HASH-TABLE} for {@link #HASH_TABLE}, the constant's own name for the
		 * rest.
		 * @return the type name
		 */
		public String typeName() {
			return name().replace('_', '-');
		}

		/**
		 * The kind a type name designates, as a {@code %operand-type-error} form spells
		 * it.
		 * @param typeName the type's symbol name
		 * @return the kind
		 * @throws IllegalArgumentException when no kind has that name
		 */
		public static Kind named(String typeName) {
			return valueOf(typeName.replace('-', '_'));
		}

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

	/** The reported name of a store through a {@code gethash} place. */
	public static final String SETF_GETHASH = "(SETF GETHASH)";

	/** The reported name of a store through a {@code fill-pointer} place. */
	public static final String SETF_FILL_POINTER = "(SETF FILL-POINTER)";

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

	/**
	 * The type the fill-pointer surface ({@code fill-pointer} and its {@code setf},
	 * {@code vector-push}, {@code vector-push-extend}, {@code vector-pop}) requires of an
	 * ARRAY it reads, as nested lists of symbol names: {@code (AND VECTOR (SATISFIES
	 * ARRAY-HAS-FILL-POINTER-P))}, the expected type SBCL's run-time check carries. A
	 * value that is no array at all reports {@code ARRAY} first, as every array accessor
	 * does ({@link #typeText} spells it).
	 */
	public static final List<Object> FILL_POINTER_VECTOR_TYPE = List.of("AND", "VECTOR",
			List.of("SATISFIES", "ARRAY-HAS-FILL-POINTER-P"));

	/**
	 * What {@code vector-pop} of a vector whose fill pointer is 0 signals: a
	 * {@code simple-error} (CLHS: "an error of type error"), not a {@code type-error} --
	 * the vector is of the type the operator requires. One text for every backend.
	 */
	public static final String VECTOR_POP_EMPTY = "VECTOR-POP: there is nothing left to pop";

	/**
	 * The symbol name that opens a fill pointer's range type, {@link #fillPointerType}.
	 */
	public static final String INTEGER_TYPE = "INTEGER";

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
	 * ({@code BuiltinFunctionWrappers}). {@code notany}/{@code notevery} are
	 * {@code (not (some ...))}/{@code (not (every ...))}, {@code copy-seq} is
	 * {@code (subseq x 0)}. {@code %set-fill-pointer} is a {@code fill-pointer} place's
	 * store, {@code %array-disp-target} {@code array-displacement}'s primary value and
	 * {@code %elt-cell} {@code elt}'s list arm.
	 */
	private static final Map<String, String> REWRITTEN = Map.ofEntries(Map.entry("1+", "+"), Map.entry("1-", "-"),
			Map.entry("/=", "="), Map.entry("ZEROP", "="), Map.entry("PLUSP", ">"), Map.entry("MINUSP", "<"),
			Map.entry("EVENP", "MOD"), Map.entry("ODDP", "MOD"), Map.entry("LOGTEST", "LOGAND"),
			Map.entry("LOGEQV", "LOGXOR"), Map.entry("FIRST", "CAR"), Map.entry("REST", "CDR"),
			Map.entry("NTH", "NTHCDR"), Map.entry("SVREF", "AREF"), Map.entry("%ASET", SETF_AREF),
			Map.entry("%ROW-MAJOR-ASET", SETF_ROW_MAJOR_AREF), Map.entry("NOTANY", "SOME"),
			Map.entry("NOTEVERY", "EVERY"), Map.entry("COPY-SEQ", "SUBSEQ"), Map.entry("%PUTHASH", SETF_GETHASH),
			Map.entry("%SET-FILL-POINTER", SETF_FILL_POINTER), Map.entry("%ARRAY-DISP-TARGET", "ARRAY-DISPLACEMENT"),
			Map.entry("%ELT-CELL", "ELT"));

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

	/**
	 * The sequence operators, funnel-typed too: a lowering whose own type dispatch runs
	 * out of arms signals {@code SEQUENCE} under the operator it expands
	 * ({@code %operand-type-error}), the interpreter's shared conversion
	 * ({@code Environment.seqAsList}) the same kind for the seam to name, and a subscript
	 * or bound they check stays an {@code INTEGER}. Last in the table, so a module that
	 * spells none of them numbers every other operator as before.
	 */
	private static final List<String> SEQUENCE_OPERATORS = List.of("EVERY", "SOME", "SORT", "STABLE-SORT", "FIND",
			"FIND-IF", "FIND-IF-NOT", "POSITION", "POSITION-IF", "POSITION-IF-NOT", "COUNT", "COUNT-IF", "COUNT-IF-NOT",
			"REMOVE", "REMOVE-IF", "REMOVE-IF-NOT", "DELETE", "DELETE-IF", "DELETE-IF-NOT", "SUBSTITUTE",
			"SUBSTITUTE-IF", "SUBSTITUTE-IF-NOT", "NSUBSTITUTE", "NSUBSTITUTE-IF", "NSUBSTITUTE-IF-NOT",
			"REMOVE-DUPLICATES", "DELETE-DUPLICATES", "REDUCE", "SUBSEQ", "FILL", "REPLACE", "CONCATENATE", "COERCE",
			"MAP", "MAP-INTO", "MISMATCH", "SEARCH");

	/**
	 * The accessors of an array's shape and of a hash table, funnel-typed like
	 * {@code aref}: an operand that is no array lands {@code ARRAY}, one that is no hash
	 * table {@code HASH-TABLE}. {@code (setf gethash)} is {@code %puthash}'s reported
	 * name. Last in the table, after the sequence operators.
	 */
	private static final List<String> HASH_TABLE_OPERATORS = List.of("GETHASH", SETF_GETHASH, "REMHASH", "CLRHASH",
			"MAPHASH", "HASH-TABLE-COUNT", "HASH-TABLE-SIZE", "HASH-TABLE-TEST", "HASH-TABLE-REHASH-SIZE",
			"HASH-TABLE-REHASH-THRESHOLD");

	/**
	 * The character comparisons, fixed-typed {@code CHARACTER}: every argument is
	 * checked, the single one of a one-argument call included. Last in the table, after
	 * the hash-table accessors.
	 */
	private static final List<String> CHARACTER_OPERATORS = List.of("CHAR=", "CHAR/=", "CHAR<", "CHAR>", "CHAR<=",
			"CHAR>=", "CHAR-EQUAL", "CHAR-NOT-EQUAL", "CHAR-LESSP", "CHAR-GREATERP", "CHAR-NOT-GREATERP",
			"CHAR-NOT-LESSP");

	/**
	 * The accessors of a vector's fill pointer, adjustability, displacement and element
	 * type, funnel-typed like {@code aref}: an operand that is no array lands
	 * {@code ARRAY}. {@code (setf fill-pointer)} is {@code %set-fill-pointer}'s reported
	 * name, {@code array-displacement} {@code %array-disp-target}'s. After the character
	 * comparisons.
	 */
	private static final List<String> ARRAY_SHAPE_OPERATORS = List.of("FILL-POINTER", SETF_FILL_POINTER, "VECTOR-PUSH",
			"VECTOR-PUSH-EXTEND", "VECTOR-POP", "ARRAY-ELEMENT-TYPE", "ADJUSTABLE-ARRAY-P", "ARRAY-HAS-FILL-POINTER-P",
			"ARRAY-DISPLACEMENT", "ADJUST-ARRAY");

	/**
	 * The other character built-ins, fixed-typed {@code CHARACTER} like the comparisons:
	 * {@code char-code}, {@code char-int}, the case folds and the character predicates.
	 * Last in the table, after the array-shape accessors.
	 */
	private static final List<String> CHARACTER_BUILTINS = List.of("CHAR-CODE", "CHAR-INT", "CHAR-UPCASE",
			"CHAR-DOWNCASE", "ALPHA-CHAR-P", "UPPER-CASE-P", "LOWER-CASE-P", "BOTH-CASE-P", "ALPHANUMERICP",
			"CHAR-NAME", "GRAPHIC-CHAR-P", "STANDARD-CHAR-P");

	/**
	 * {@code digit-char-p}, funnel-typed: its character lands {@code CHARACTER}, its
	 * radix {@code INTEGER}. After {@link #CHARACTER_BUILTINS}.
	 */
	private static final String DIGIT_CHAR_P = "DIGIT-CHAR-P";

	/**
	 * {@code elt}'s list arm ({@code %elt-cell}, reported as {@code ELT}), funnel-typed:
	 * its index lands {@code INTEGER} -- {@code (INTEGER 0 (length))} when it is outside
	 * the list -- and a non-list met on the walk {@code LIST}. Last in the table.
	 */
	private static final String ELT = "ELT";

	/** The operators whose sites can land {@code CHARACTER}. */
	private static final List<String> CHARACTER_OPERATORS_ALL = java.util.stream.Stream
		.of(CHARACTER_OPERATORS.stream(), CHARACTER_BUILTINS.stream(), java.util.stream.Stream.of(DIGIT_CHAR_P))
		.flatMap(s -> s)
		.toList();

	/** The operators whose sites can land {@code ARRAY}. */
	private static final List<String> ARRAY_OPERATORS = java.util.stream.Stream
		.concat(java.util.stream.Stream.of("AREF", SETF_AREF, "ROW-MAJOR-AREF", SETF_ROW_MAJOR_AREF,
				"ARRAY-DIMENSIONS"), ARRAY_SHAPE_OPERATORS.stream())
		.toList();

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
		for (String op : SEQUENCE_OPERATORS) {
			OPERATOR_TYPES.put(op, FUNNEL_TYPE);
			order.add(op);
		}
		for (String op : java.util.stream.Stream
			.concat(java.util.stream.Stream.of("ARRAY-DIMENSIONS"), HASH_TABLE_OPERATORS.stream())
			.toList()) {
			OPERATOR_TYPES.put(op, FUNNEL_TYPE);
			order.add(op);
		}
		for (String op : CHARACTER_OPERATORS) {
			OPERATOR_TYPES.put(op, Kind.CHARACTER.name());
			order.add(op);
		}
		for (String op : ARRAY_SHAPE_OPERATORS) {
			OPERATOR_TYPES.put(op, FUNNEL_TYPE);
			order.add(op);
		}
		for (String op : CHARACTER_BUILTINS) {
			OPERATOR_TYPES.put(op, Kind.CHARACTER.name());
			order.add(op);
		}
		OPERATOR_TYPES.put(DIGIT_CHAR_P, FUNNEL_TYPE);
		order.add(DIGIT_CHAR_P);
		OPERATOR_TYPES.put(ELT, FUNNEL_TYPE);
		order.add(ELT);
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
	 * {@code SEQUENCE}, {@code CONS} or {@code CHARACTER}, {@link #FUNNEL_TYPE} for a
	 * funnel-typed operator, or null for an operator that is not named
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
	 * The funnel-typed sequence operators, whose checks land {@link Kind#SEQUENCE}: a
	 * backend that selects a report's type among the kinds its module can reach adds it
	 * when one of these is named.
	 * @return the operator names
	 */
	public static List<String> sequenceOperators() {
		return SEQUENCE_OPERATORS;
	}

	/**
	 * The operators whose checks land {@link Kind#ARRAY}, as {@link #sequenceOperators()}
	 * is for {@code SEQUENCE}.
	 * @return the operator names
	 */
	public static List<String> arrayOperators() {
		return ARRAY_OPERATORS;
	}

	/**
	 * The operators whose checks land {@link Kind#HASH_TABLE}, as
	 * {@link #sequenceOperators()} is for {@code SEQUENCE}.
	 * @return the operator names
	 */
	public static List<String> hashTableOperators() {
		return HASH_TABLE_OPERATORS;
	}

	/**
	 * The operators whose checks land {@link Kind#CHARACTER}: the comparisons, the other
	 * character built-ins and {@code digit-char-p}, as {@link #sequenceOperators()} is
	 * for {@code SEQUENCE}.
	 * @return the operator names
	 */
	public static List<String> characterOperators() {
		return CHARACTER_OPERATORS_ALL;
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
			return kind.typeName();
		}
		if (FUNNEL_TYPE.equals(type)) {
			return (kind == Kind.NUMBER ? Kind.REAL : kind).typeName();
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
	 * The type a fill pointer stored into a vector of {@code dimension} elements is not
	 * of when it falls outside the vector: {@code (INTEGER 0 dimension)} -- the range is
	 * inclusive, a fill pointer may equal the dimension. Nested lists like
	 * {@link #FILL_POINTER_VECTOR_TYPE}, the bounds as {@code Long}s.
	 * @param dimension the vector's dimension
	 * @return the type
	 */
	public static List<Object> fillPointerType(long dimension) {
		return integerRange(0, dimension);
	}

	/**
	 * The inclusive integer range {@code (INTEGER low high)}, nested lists like
	 * {@link #FILL_POINTER_VECTOR_TYPE}.
	 * @param low the least member
	 * @param high the greatest member
	 * @return the type
	 */
	public static List<Object> integerRange(long low, long high) {
		return List.of(INTEGER_TYPE, low, high);
	}

	/**
	 * Whether {@code subseq}'s refused bound is its START: a type error names the first
	 * bounding index outside its range (CLHS 17.1.1) -- {@code start} outside
	 * {@code [0, length]}, else {@code end} outside {@code [start, length]} -- so the
	 * datum is that bound and the expected type {@code (INTEGER low length)}, {@code low}
	 * being 0 for the start and the start for the end. Every backend decides it this way.
	 * @param start the start
	 * @param length the sequence's length
	 * @return whether the start is the refused bound
	 */
	public static boolean subseqStartRefused(long start, long length) {
		return start < 0 || start > length;
	}

	/**
	 * A compound type's printed text: a string is a symbol's name, a number itself, a
	 * list its elements in parentheses -- {@code (INTEGER 0 3)}.
	 * @param type a type as {@link #FILL_POINTER_VECTOR_TYPE} spells one
	 * @return the text
	 */
	public static String typeText(Object type) {
		if (type instanceof List<?> list) {
			StringBuilder text = new StringBuilder("(");
			for (int i = 0; i < list.size(); i++) {
				text.append(i == 0 ? "" : " ").append(typeText(list.get(i)));
			}
			return text.append(')').toString();
		}
		return String.valueOf(type);
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

package am.ik.rontolisp;

/**
 * Centralized constants for all special form and built-in function names. These constants
 * are compile-time constants (static final String) and can be used in switch case labels.
 */
public final class LispNames {

	// Special forms
	public static final String QUOTE = "quote";

	public static final String IF = "if";

	public static final String LET = "let";

	public static final String PROGN = "progn";

	public static final String SETQ = "setq";

	public static final String LAMBDA = "lambda";

	public static final String FUNCALL = "funcall";

	// Arithmetic
	public static final String ADD = "+";

	public static final String SUB = "-";

	public static final String MUL = "*";

	public static final String DIV = "/";

	public static final String MOD = "mod";

	// Comparison
	public static final String EQ = "=";

	public static final String LT = "<";

	public static final String GT = ">";

	public static final String LE = "<=";

	public static final String GE = ">=";

	// List operations
	public static final String CONS = "cons";

	public static final String CAR = "car";

	public static final String CDR = "cdr";

	public static final String LIST = "list";

	// Macros
	public static final String DEFUN = "defun";

	public static final String COND = "cond";

	public static final String AND = "and";

	public static final String OR = "or";

	public static final String NOT = "not";

	// Type predicates
	public static final String NULL = "null";

	public static final String ATOM = "atom";

	public static final String NUMBERP = "numberp";

	public static final String INTEGERP = "integerp";

	public static final String FLOATP = "floatp";

	public static final String SYMBOLP = "symbolp";

	public static final String STRINGP = "stringp";

	public static final String LISTP = "listp";

	public static final String CONSP = "consp";

	// Type conversion
	public static final String FLOAT = "float";

	public static final String TRUNCATE = "truncate";

	public static final String FLOOR = "floor";

	public static final String CEILING = "ceiling";

	public static final String ROUND = "round";

	// I/O
	public static final String PRINT = "print";

	private LispNames() {
	}

}

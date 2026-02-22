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

	public static final String APPEND = "append";

	// Macros
	public static final String DEFUN = "defun";

	public static final String COND = "cond";

	public static final String AND = "and";

	public static final String OR = "or";

	public static final String NOT = "not";

	public static final String WHEN = "when";

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

	// Convenience macros
	public static final String ONE_PLUS = "1+";

	public static final String ONE_MINUS = "1-";

	public static final String ZEROP = "zerop";

	public static final String PLUSP = "plusp";

	public static final String MINUSP = "minusp";

	public static final String EVENP = "evenp";

	public static final String ODDP = "oddp";

	public static final String ABS = "abs";

	public static final String MIN = "min";

	public static final String MAX = "max";

	public static final String UNLESS = "unless";

	public static final String SECOND = "second";

	public static final String THIRD = "third";

	public static final String FOURTH = "fourth";

	// I/O
	public static final String PRINT = "print";

	private LispNames() {
	}

}

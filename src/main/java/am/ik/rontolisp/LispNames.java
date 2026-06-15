package am.ik.rontolisp;

/**
 * Centralized constants for all special form and built-in function names. These constants
 * are compile-time constants (static final String) and can be used in switch case labels.
 */
public final class LispNames {

	// Special forms

	/** The {@code quote} special form. */
	public static final String QUOTE = "quote";

	/** The {@code if} special form. */
	public static final String IF = "if";

	/** The {@code let} special form. */
	public static final String LET = "let";

	/** The {@code progn} special form. */
	public static final String PROGN = "progn";

	/** The {@code setq} special form. */
	public static final String SETQ = "setq";

	/** The {@code lambda} special form. */
	public static final String LAMBDA = "lambda";

	/** The {@code funcall} special form. */
	public static final String FUNCALL = "funcall";

	/** The {@code function} special form ({@code #'name} reader syntax). */
	public static final String FUNCTION = "function";

	/** The {@code symbol-function} built-in function. */
	public static final String SYMBOL_FUNCTION = "symbol-function";

	/** The {@code while} special form. */
	public static final String WHILE = "while";

	// Arithmetic

	/** The {@code +} built-in function. */
	public static final String ADD = "+";

	/** The {@code -} built-in function. */
	public static final String SUB = "-";

	/** The {@code *} built-in function. */
	public static final String MUL = "*";

	/** The {@code /} built-in function. */
	public static final String DIV = "/";

	/** The {@code mod} built-in function. */
	public static final String MOD = "mod";

	/** The {@code rem} built-in function. */
	public static final String REM = "rem";

	/** The {@code abs} built-in function. */
	public static final String ABS = "abs";

	/** The {@code min} built-in function. */
	public static final String MIN = "min";

	/** The {@code max} built-in function. */
	public static final String MAX = "max";

	/** The {@code sqrt} built-in function. */
	public static final String SQRT = "sqrt";

	/** The {@code isqrt} built-in function. */
	public static final String ISQRT = "isqrt";

	/** The {@code expt} built-in function. */
	public static final String EXPT = "expt";

	/** The {@code exp} built-in function. */
	public static final String EXP = "exp";

	/** The {@code log} built-in function. */
	public static final String LOG = "log";

	/** The {@code sin} built-in function. */
	public static final String SIN = "sin";

	/** The {@code cos} built-in function. */
	public static final String COS = "cos";

	/** The {@code tan} built-in function. */
	public static final String TAN = "tan";

	/** The {@code asin} built-in function. */
	public static final String ASIN = "asin";

	/** The {@code acos} built-in function. */
	public static final String ACOS = "acos";

	/** The {@code atan} built-in function. */
	public static final String ATAN = "atan";

	/** The {@code sinh} built-in function. */
	public static final String SINH = "sinh";

	/** The {@code cosh} built-in function. */
	public static final String COSH = "cosh";

	/** The {@code tanh} built-in function. */
	public static final String TANH = "tanh";

	/** The {@code gcd} built-in function. */
	public static final String GCD = "gcd";

	/** The {@code lcm} built-in function. */
	public static final String LCM = "lcm";

	/** The {@code signum} built-in function. */
	public static final String SIGNUM = "signum";

	// Comparison

	/** The {@code =} built-in function. */
	public static final String EQ = "=";

	/** The {@code eq} built-in function (general equality). */
	public static final String EQ_GENERAL = "eq";

	/** The {@code eql} built-in function (type-aware value equality). */
	public static final String EQL = "eql";

	/** The {@code equal} built-in function (structural equality). */
	public static final String EQUAL = "equal";

	/** The {@code <} built-in function. */
	public static final String LT = "<";

	/** The {@code >} built-in function. */
	public static final String GT = ">";

	/** The {@code <=} built-in function. */
	public static final String LE = "<=";

	/** The {@code >=} built-in function. */
	public static final String GE = ">=";

	// List operations

	/** The {@code cons} built-in function. */
	public static final String CONS = "cons";

	/** The {@code car} built-in function. */
	public static final String CAR = "car";

	/** The {@code cdr} built-in function. */
	public static final String CDR = "cdr";

	/** The {@code list} built-in function. */
	public static final String LIST = "list";

	/** The {@code append} built-in function. */
	public static final String APPEND = "append";

	/** The {@code nthcdr} built-in function. */
	public static final String NTHCDR = "nthcdr";

	/** The {@code length} built-in function. */
	public static final String LENGTH = "length";

	/** The {@code reverse} built-in function. */
	public static final String REVERSE = "reverse";

	/** The {@code member} built-in function. */
	public static final String MEMBER = "member";

	/** The {@code assoc} built-in function. */
	public static final String ASSOC = "assoc";

	/** The {@code last} built-in function. */
	public static final String LAST = "last";

	/** The {@code rplaca} built-in function. */
	public static final String RPLACA = "rplaca";

	/** The {@code rplacd} built-in function. */
	public static final String RPLACD = "rplacd";

	/** The {@code %remf-tail} built-in function. */
	public static final String REMF_TAIL = "%remf-tail";

	// Higher-order functions

	/** The {@code mapcar} built-in function. */
	public static final String MAPCAR = "mapcar";

	/** The {@code mapc} built-in function (apply for effect, return the list). */
	public static final String MAPC = "mapc";

	/** The {@code reduce} built-in function. */
	public static final String REDUCE = "reduce";

	// Macros

	/** The {@code setf} macro. */
	public static final String SETF = "setf";

	/** The {@code push} macro. */
	public static final String PUSH = "push";

	/** The {@code pop} macro. */
	public static final String POP = "pop";

	/** The {@code remf} macro. */
	public static final String REMF = "remf";

	/** The {@code let*} macro. */
	public static final String LET_STAR = "let*";

	/** The {@code dolist} macro. */
	public static final String DOLIST = "dolist";

	/** The {@code incf} macro. */
	public static final String INCF = "incf";

	/** The {@code decf} macro. */
	public static final String DECF = "decf";

	/** The {@code format} macro. */
	public static final String FORMAT = "format";

	/** The {@code defun} macro. */
	public static final String DEFUN = "defun";

	/** The {@code defvar} special form. */
	public static final String DEFVAR = "defvar";

	/** The {@code cond} macro. */
	public static final String COND = "cond";

	/** The {@code case} macro. */
	public static final String CASE = "case";

	/** The {@code otherwise} default-clause designator recognized by {@code case}. */
	public static final String OTHERWISE = "otherwise";

	/** The {@code and} macro. */
	public static final String AND = "and";

	/** The {@code or} macro. */
	public static final String OR = "or";

	/** The {@code not} built-in function. */
	public static final String NOT = "not";

	/** The {@code when} macro. */
	public static final String WHEN = "when";

	/** The {@code dotimes} macro. */
	public static final String DOTIMES = "dotimes";

	/** The {@code prog1} macro. */
	public static final String PROG1 = "prog1";

	/** The {@code do} macro (parallel iteration). */
	public static final String DO = "do";

	/** The {@code return} special form (non-local exit from the nearest loop block). */
	public static final String RETURN = "return";

	/**
	 * The internal {@code %block} special form establishing the {@code return} boundary
	 * that the loop macros ({@code do}/{@code dolist}/{@code dotimes}) wrap their
	 * expansion in. Not part of the public Lisp API.
	 */
	public static final String BLOCK_INTERNAL = "%block";

	// Type predicates

	/** The {@code null} built-in function. */
	public static final String NULL = "null";

	/** The {@code atom} built-in function. */
	public static final String ATOM = "atom";

	/** The {@code numberp} built-in function. */
	public static final String NUMBERP = "numberp";

	/** The {@code integerp} built-in function. */
	public static final String INTEGERP = "integerp";

	/** The {@code floatp} built-in function. */
	public static final String FLOATP = "floatp";

	/** The {@code rationalp} built-in function. */
	public static final String RATIONALP = "rationalp";

	/** The {@code symbolp} built-in function. */
	public static final String SYMBOLP = "symbolp";

	/** The {@code stringp} built-in function. */
	public static final String STRINGP = "stringp";

	/** The {@code listp} built-in function. */
	public static final String LISTP = "listp";

	/** The {@code consp} built-in function. */
	public static final String CONSP = "consp";

	/** The {@code keywordp} built-in function. */
	public static final String KEYWORDP = "keywordp";

	// Type conversion

	/** The {@code float} built-in function. */
	public static final String FLOAT = "float";

	/** The {@code truncate} built-in function. */
	public static final String TRUNCATE = "truncate";

	/** The {@code floor} built-in function. */
	public static final String FLOOR = "floor";

	/** The {@code ceiling} built-in function. */
	public static final String CEILING = "ceiling";

	/** The {@code round} built-in function. */
	public static final String ROUND = "round";

	/** The {@code numerator} built-in function. */
	public static final String NUMERATOR = "numerator";

	/** The {@code denominator} built-in function. */
	public static final String DENOMINATOR = "denominator";

	// Convenience macros

	/** The {@code 1+} macro. */
	public static final String ONE_PLUS = "1+";

	/** The {@code 1-} macro. */
	public static final String ONE_MINUS = "1-";

	/** The {@code zerop} macro. */
	public static final String ZEROP = "zerop";

	/** The {@code plusp} macro. */
	public static final String PLUSP = "plusp";

	/** The {@code minusp} macro. */
	public static final String MINUSP = "minusp";

	/** The {@code evenp} macro. */
	public static final String EVENP = "evenp";

	/** The {@code oddp} macro. */
	public static final String ODDP = "oddp";

	/** The {@code unless} macro. */
	public static final String UNLESS = "unless";

	/** The {@code first} macro. */
	public static final String FIRST = "first";

	/** The {@code rest} macro. */
	public static final String REST = "rest";

	/** The {@code second} macro. */
	public static final String SECOND = "second";

	/** The {@code third} macro. */
	public static final String THIRD = "third";

	/** The {@code fourth} macro. */
	public static final String FOURTH = "fourth";

	/** The {@code nth} macro. */
	public static final String NTH = "nth";

	// I/O

	/** The {@code print} built-in function. */
	public static final String PRINT = "print";

	/** The {@code prin1} built-in function. */
	public static final String PRIN1 = "prin1";

	/** The {@code princ} built-in function. */
	public static final String PRINC = "princ";

	/** The {@code terpri} built-in function. */
	public static final String TERPRI = "terpri";

	/** The {@code princ-to-string} built-in function. */
	public static final String PRINC_TO_STRING = "princ-to-string";

	/** The {@code prin1-to-string} built-in function. */
	public static final String PRIN1_TO_STRING = "prin1-to-string";

	/** The {@code concatenate} built-in function (only {@code 'string} is supported). */
	public static final String CONCATENATE = "concatenate";

	/** The {@code %string-concat} built-in function. */
	public static final String STRING_CONCAT = "%string-concat";

	/** The {@code read-line} built-in function. */
	public static final String READ_LINE = "read-line";

	// String operations

	/** The {@code string-upcase} built-in function. */
	public static final String STRING_UPCASE = "string-upcase";

	/** The {@code string-downcase} built-in function. */
	public static final String STRING_DOWNCASE = "string-downcase";

	/** The {@code string-capitalize} built-in function. */
	public static final String STRING_CAPITALIZE = "string-capitalize";

	/** The {@code subseq} built-in function (strings only). */
	public static final String SUBSEQ = "subseq";

	/** The {@code string=} built-in function (case-sensitive string equality). */
	public static final String STRING_EQ = "string=";

	/** The {@code string-equal} built-in function (case-insensitive string equality). */
	public static final String STRING_EQUAL = "string-equal";

	/** The {@code string-trim} built-in function. */
	public static final String STRING_TRIM = "string-trim";

	/** The {@code string-left-trim} built-in function. */
	public static final String STRING_LEFT_TRIM = "string-left-trim";

	/** The {@code string-right-trim} built-in function. */
	public static final String STRING_RIGHT_TRIM = "string-right-trim";

	/** The {@code read} built-in function (interpreter only). */
	public static final String READ = "read";

	/** The {@code eval} built-in function (interpreter only). */
	public static final String EVAL = "eval";

	/** The {@code load} built-in function (interpreter only). */
	public static final String LOAD = "load";

	// File I/O

	/** The {@code open} built-in function. */
	public static final String OPEN = "open";

	/** The {@code close} built-in function. */
	public static final String CLOSE = "close";

	/** The {@code write-line} built-in function. */
	public static final String WRITE_LINE = "write-line";

	/** The {@code with-open-file} macro. */
	public static final String WITH_OPEN_FILE = "with-open-file";

	/** The {@code :direction} keyword recognized by {@code with-open-file}. */
	public static final String DIRECTION_KEYWORD = ":direction";

	/** The {@code :input} keyword (open a file for reading). */
	public static final String INPUT_KEYWORD = ":input";

	/** The {@code :output} keyword (open a file for writing). */
	public static final String OUTPUT_KEYWORD = ":output";

	// Packages

	/** The {@code in-package} directive that switches the current package. */
	public static final String IN_PACKAGE = "in-package";

	/** The {@code *package*} variable holding the current package name. */
	public static final String PACKAGE_VAR = "*package*";

	/** The {@code version} function provided by the {@code rontolisp} package. */
	public static final String VERSION = "version";

	/** The {@code list-functions} function provided by the {@code rontolisp} package. */
	public static final String LIST_FUNCTIONS = "list-functions";

	/** The {@code list-macros} function provided by the {@code rontolisp} package. */
	public static final String LIST_MACROS = "list-macros";

	/**
	 * The {@code list-special-forms} function provided by the {@code rontolisp} package.
	 */
	public static final String LIST_SPECIAL_FORMS = "list-special-forms";

	/** The {@code cl} package name (standard functions, macros and variables). */
	public static final String CL_PKG = "cl";

	/** The {@code cl-user} package name (default working package, uses {@code cl}). */
	public static final String CL_USER_PKG = "cl-user";

	/** The {@code rontolisp} package name (does not use {@code cl}). */
	public static final String RONTOLISP_PKG = "rontolisp";

	private LispNames() {
	}

}

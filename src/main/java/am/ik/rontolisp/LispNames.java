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

	/** The {@code random} built-in function. */
	public static final String RANDOM = "random";

	/** The {@code get-universal-time} built-in function. */
	public static final String GET_UNIVERSAL_TIME = "get-universal-time";

	/** The {@code get-internal-real-time} built-in function. */
	public static final String GET_INTERNAL_REAL_TIME = "get-internal-real-time";

	/** The {@code get-internal-run-time} built-in function. */
	public static final String GET_INTERNAL_RUN_TIME = "get-internal-run-time";

	/** The {@code getenv} built-in function. */
	public static final String GETENV = "getenv";

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

	/** The {@code logand} built-in function (bitwise AND, variadic; identity -1). */
	public static final String LOGAND = "logand";

	/**
	 * The {@code logior} built-in function (bitwise inclusive OR, variadic; identity 0).
	 */
	public static final String LOGIOR = "logior";

	/**
	 * The {@code logxor} built-in function (bitwise exclusive OR, variadic; identity 0).
	 */
	public static final String LOGXOR = "logxor";

	/** The {@code lognot} built-in function (bitwise NOT, i.e. ones' complement). */
	public static final String LOGNOT = "lognot";

	/**
	 * The {@code ash} built-in function (arithmetic shift; left when the count is
	 * non-negative, right otherwise).
	 */
	public static final String ASH = "ash";

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

	/**
	 * The {@code find} built-in function (return the first element {@code eql} to the
	 * given item, or nil).
	 */
	public static final String FIND = "find";

	/**
	 * The {@code find-if} built-in function (return the first element for which the
	 * predicate is true, or nil).
	 */
	public static final String FIND_IF = "find-if";

	/**
	 * The {@code find-if-not} built-in function (return the first element for which the
	 * predicate is false, or nil).
	 */
	public static final String FIND_IF_NOT = "find-if-not";

	/**
	 * The {@code member-if} built-in function (return the tail of the list starting at
	 * the first element for which the predicate is true, or nil).
	 */
	public static final String MEMBER_IF = "member-if";

	/**
	 * The {@code position} built-in function (return the 0-based index of the first
	 * element {@code eql} to the given item, or nil).
	 */
	public static final String POSITION = "position";

	/**
	 * The {@code position-if} built-in function (return the 0-based index of the first
	 * element for which the predicate is true, or nil).
	 */
	public static final String POSITION_IF = "position-if";

	/**
	 * The {@code count} built-in function (return the number of elements {@code eql} to
	 * the given item).
	 */
	public static final String COUNT = "count";

	/**
	 * The {@code count-if} built-in function (return the number of elements for which the
	 * predicate is true).
	 */
	public static final String COUNT_IF = "count-if";

	/** The {@code assoc} built-in function. */
	public static final String ASSOC = "assoc";

	/**
	 * The {@code assoc-if} built-in function (return the first pair whose car satisfies
	 * the predicate, or nil).
	 */
	public static final String ASSOC_IF = "assoc-if";

	/** The {@code last} built-in function. */
	public static final String LAST = "last";

	/**
	 * The {@code butlast} built-in function (return a copy of the list without its last
	 * element).
	 */
	public static final String BUTLAST = "butlast";

	/**
	 * The {@code getf} built-in function (return the value following the indicator in a
	 * property list, or nil). The partner of {@code remf}.
	 */
	public static final String GETF = "getf";

	/**
	 * The {@code remove} built-in function (return a copy without items eql to the given
	 * one).
	 */
	public static final String REMOVE = "remove";

	/**
	 * The {@code remove-if} built-in function (return a copy without items satisfying a
	 * predicate).
	 */
	public static final String REMOVE_IF = "remove-if";

	/**
	 * The {@code remove-if-not} built-in function (return a copy keeping only items
	 * satisfying a predicate).
	 */
	public static final String REMOVE_IF_NOT = "remove-if-not";

	/**
	 * The {@code remove-duplicates} built-in function (return a copy of the list with
	 * duplicate elements removed, keeping the last occurrence; elements compared with
	 * {@code eql}).
	 */
	public static final String REMOVE_DUPLICATES = "remove-duplicates";

	/**
	 * The {@code delete} built-in function (destructive variant of {@code remove}:
	 * splices out every element {@code eql} to the given one in place, reusing the
	 * surviving cons cells; use the return value since the head may change).
	 */
	public static final String DELETE = "delete";

	/**
	 * The {@code delete-if} built-in function (destructive variant of {@code remove-if};
	 * see {@link #DELETE}).
	 */
	public static final String DELETE_IF = "delete-if";

	/**
	 * The {@code delete-if-not} built-in function (destructive variant of
	 * {@code remove-if-not}; see {@link #DELETE}).
	 */
	public static final String DELETE_IF_NOT = "delete-if-not";

	/**
	 * The {@code substitute} built-in function (return a copy of the list with each
	 * element {@code eql} to the old item replaced by the new item).
	 */
	public static final String SUBSTITUTE = "substitute";

	/**
	 * The {@code nsubstitute} built-in function (destructive variant of
	 * {@code substitute}: rewrites every {@code car} {@code eql} to the old item with the
	 * new item in place and returns the mutated list).
	 */
	public static final String NSUBSTITUTE = "nsubstitute";

	/**
	 * The {@code nconc} built-in function (destructively concatenate two lists).
	 */
	public static final String NCONC = "nconc";

	/**
	 * The {@code sort} built-in function (destructively sort a list using a comparison
	 * predicate).
	 */
	public static final String SORT = "sort";

	/** The {@code identity} built-in function (returns its argument unchanged). */
	public static final String IDENTITY = "identity";

	/** The {@code copy-list} built-in function (returns a shallow copy of a list). */
	public static final String COPY_LIST = "copy-list";

	/**
	 * The {@code nreverse} built-in function (destructively reverses a list by rewiring
	 * each {@code cdr} and returning the former last cell as the new head; use the return
	 * value).
	 */
	public static final String NREVERSE = "nreverse";

	/**
	 * The {@code make-list} built-in function (creates a list of n nil elements; the CL
	 * {@code :initial-element} keyword is not supported).
	 */
	public static final String MAKE_LIST = "make-list";

	/**
	 * The {@code union} built-in function (set union of two lists, compared with
	 * {@code eql}; CL {@code :test}/{@code :key} keywords are not supported).
	 */
	public static final String UNION = "union";

	/**
	 * The {@code intersection} built-in function (set intersection of two lists, compared
	 * with {@code eql}).
	 */
	public static final String INTERSECTION = "intersection";

	/**
	 * The {@code set-difference} built-in function (elements of the first list not
	 * present in the second, compared with {@code eql}).
	 */
	public static final String SET_DIFFERENCE = "set-difference";

	/**
	 * The {@code adjoin} built-in function (prepends an item to a list unless it is
	 * already a member, compared with {@code eql}).
	 */
	public static final String ADJOIN = "adjoin";

	/**
	 * The {@code list*} built-in function (build a list whose final element is the last
	 * argument used as the tail: {@code (list* a b c) -> (cons a (cons b c))}).
	 */
	public static final String LIST_STAR = "list*";

	/**
	 * The {@code acons} built-in function (prepend a {@code (key . value)} pair to an
	 * association list: {@code (acons k v alist) -> (cons (cons k v) alist)}).
	 */
	public static final String ACONS = "acons";

	/**
	 * The {@code endp} built-in function (true at the end of a list; here a synonym for
	 * {@code null}, the improper-list error of CL is relaxed).
	 */
	public static final String ENDP = "endp";

	/**
	 * The {@code elt} built-in function (0-based element access; lists only, a synonym
	 * for {@code nth} with reversed argument order, string indexing is not supported).
	 */
	public static final String ELT = "elt";

	/**
	 * The {@code rassoc} built-in function (return the first pair whose cdr is
	 * {@code eql} to the given value, or nil).
	 */
	public static final String RASSOC = "rassoc";

	/**
	 * The {@code revappend} built-in function (reverse the first list and append the
	 * second: {@code (revappend x y) -> (append (reverse x) y)}).
	 */
	public static final String REVAPPEND = "revappend";

	/**
	 * The {@code nreconc} built-in function (destructive {@code revappend}: expands to
	 * {@code (nconc (nreverse x) y)}, so the cons cells of {@code x} are reused; use the
	 * return value).
	 */
	public static final String NRECONC = "nreconc";

	/**
	 * The {@code maplist} built-in function (apply the function to successive cdrs of the
	 * list and collect the results; single-list only).
	 */
	public static final String MAPLIST = "maplist";

	/**
	 * The {@code mapcon} built-in function (apply the function to successive cdrs of the
	 * list and concatenate the result lists; single-list only).
	 */
	public static final String MAPCON = "mapcon";

	/** The {@code rplaca} built-in function. */
	public static final String RPLACA = "rplaca";

	/** The {@code rplacd} built-in function. */
	public static final String RPLACD = "rplacd";

	/** The {@code %remf-tail} built-in function. */
	public static final String REMF_TAIL = "%remf-tail";

	// Hash tables

	/** The {@code make-hash-table} built-in function. */
	public static final String MAKE_HASH_TABLE = "make-hash-table";

	/** The {@code gethash} built-in function. */
	public static final String GETHASH = "gethash";

	/**
	 * The {@code %puthash} internal built-in function. The target of the {@code gethash}
	 * {@code setf} place: {@code (%puthash key table value)} stores and returns the
	 * value.
	 */
	public static final String PUTHASH = "%puthash";

	/** The {@code remhash} built-in function. */
	public static final String REMHASH = "remhash";

	/** The {@code clrhash} built-in function. */
	public static final String CLRHASH = "clrhash";

	/** The {@code hash-table-count} built-in function. */
	public static final String HASH_TABLE_COUNT = "hash-table-count";

	/** The {@code hash-table-p} predicate. */
	public static final String HASH_TABLE_P = "hash-table-p";

	/**
	 * The {@code maphash} built-in function (apply a function to each key/value pair).
	 */
	public static final String MAPHASH = "maphash";

	// Arrays

	/**
	 * The {@code make-array} built-in function. Supports arrays of rank 1 and 2 and the
	 * {@code :initial-element} keyword.
	 */
	public static final String MAKE_ARRAY = "make-array";

	/** The {@code aref} built-in function (array element access). */
	public static final String AREF = "aref";

	/**
	 * The {@code %aset} internal built-in function. The target of the {@code aref}
	 * {@code setf} place: {@code (%aset array subscript... value)} stores and returns the
	 * value.
	 */
	public static final String ASET = "%aset";

	/** The {@code :initial-element} keyword accepted by {@code make-array}. */
	public static final String INITIAL_ELEMENT_KEYWORD = ":initial-element";

	// Higher-order functions

	/** The {@code mapcar} built-in function. */
	public static final String MAPCAR = "mapcar";

	/**
	 * The {@code map} built-in function (map a function over arbitrary sequences,
	 * building a result of a requested type: {@code 'list}, {@code 'string}, or nil for
	 * effect).
	 */
	public static final String MAP = "map";

	/** The {@code mapc} built-in function (apply for effect, return the list). */
	public static final String MAPC = "mapc";

	/**
	 * The {@code mapcan} built-in function (apply over a list and concatenate the result
	 * lists).
	 */
	public static final String MAPCAN = "mapcan";

	/**
	 * The {@code apply} built-in function (apply a function to a spread argument list).
	 */
	public static final String APPLY = "apply";

	/** The {@code reduce} built-in function. */
	public static final String REDUCE = "reduce";

	/**
	 * The {@code every} built-in function (true if the predicate holds for every
	 * element).
	 */
	public static final String EVERY = "every";

	/**
	 * The {@code some} built-in function (the first non-nil predicate result, or nil).
	 */
	public static final String SOME = "some";

	/**
	 * The {@code notany} built-in function (true if the predicate holds for no element;
	 * the complement of {@code some}).
	 */
	public static final String NOTANY = "notany";

	/**
	 * The {@code notevery} built-in function (true if the predicate fails for some
	 * element; the complement of {@code every}).
	 */
	public static final String NOTEVERY = "notevery";

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

	/**
	 * The {@code defmacro} special form. User macros are expanded by the interpreter at
	 * evaluation time and by a compile-time pass on the compilation path, so the JVM/WASM
	 * backends never see a macro call.
	 */
	public static final String DEFMACRO = "defmacro";

	/** The {@code &rest} lambda-list keyword. */
	public static final String LAMBDA_REST = "&rest";

	/**
	 * The {@code &body} lambda-list keyword ({@code defmacro} alias for {@code &rest}).
	 */
	public static final String LAMBDA_BODY = "&body";

	/**
	 * The {@code gensym} function. Returns a fresh symbol named
	 * {@code #:<prefix><counter>} (default prefix {@code g}). rontolisp symbols are plain
	 * strings, so the result is an ordinary symbol whose uniqueness rests on the
	 * {@code #:} prefix and a monotonically increasing counter.
	 */
	public static final String GENSYM = "gensym";

	/**
	 * The {@code macroexpand-1} function. Expands the top-level form once when its
	 * operator is a user macro or a built-in macro; returns the form unchanged otherwise
	 * (rontolisp has no multiple values, so no second {@code expanded-p} value).
	 */
	public static final String MACROEXPAND_1 = "macroexpand-1";

	/** The {@code macroexpand} function. Repeats {@code macroexpand-1} to a fixpoint. */
	public static final String MACROEXPAND = "macroexpand";

	/** The {@code defvar} special form. */
	public static final String DEFVAR = "defvar";

	/** The {@code defparameter} special form (unconditional global assignment). */
	public static final String DEFPARAMETER = "defparameter";

	/**
	 * The {@code defconstant} special form. rontolisp does not enforce constancy; it
	 * behaves like {@code defparameter}.
	 */
	public static final String DEFCONSTANT = "defconstant";

	/** The {@code cond} macro. */
	public static final String COND = "cond";

	/** The {@code case} macro. */
	public static final String CASE = "case";

	/** The {@code otherwise} default-clause designator recognized by {@code case}. */
	public static final String OTHERWISE = "otherwise";

	/**
	 * The {@code ecase} macro (exhaustive {@code case}; signals an error when no key
	 * matches).
	 */
	public static final String ECASE = "ecase";

	/**
	 * The {@code etypecase} macro (exhaustive {@code typecase}; signals an error when no
	 * type matches).
	 */
	public static final String ETYPECASE = "etypecase";

	/**
	 * The {@code ccase} macro. Without a restart system this behaves like {@code ecase}
	 * (signals an error when no key matches).
	 */
	public static final String CCASE = "ccase";

	/**
	 * The {@code error} macro (signal an error). It builds the message with the
	 * {@code format} machinery and delegates to {@link #ERROR_INTERNAL}. Like
	 * {@code format} it has no function value (classified as a macro).
	 */
	public static final String ERROR = "error";

	/**
	 * Internal single-argument primitive that throws/traps with a pre-built message
	 * string. Not part of the public API; produced by the {@code error} macro expansion.
	 */
	public static final String ERROR_INTERNAL = "%error";

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

	/**
	 * The {@code time} macro (evaluate a form, print the elapsed real time to standard
	 * output, and return the form's value).
	 */
	public static final String TIME = "time";

	/**
	 * The {@code prog2} macro (evaluate the forms in order and return the value of the
	 * second).
	 */
	public static final String PROG2 = "prog2";

	/**
	 * The {@code psetq} macro (parallel assignment: every right-hand side is evaluated
	 * before any variable is assigned).
	 */
	public static final String PSETQ = "psetq";

	/**
	 * The {@code typecase} macro (dispatch on the type of an object using the built-in
	 * type predicates).
	 */
	public static final String TYPECASE = "typecase";

	/** The {@code do} macro (parallel iteration). */
	public static final String DO = "do";

	/**
	 * The {@code do*} macro (sequential iteration; {@code let*}-style bindings/steps).
	 */
	public static final String DO_STAR = "do*";

	/**
	 * The {@code loop} macro. Only the "simple loop" subset is supported (numeric/list
	 * stepping, accumulation, simple control clauses); see
	 * {@link LispMacroExpander#expandLoop} for the exact grammar and limitations.
	 */
	public static final String LOOP = "loop";

	/** The {@code return} special form (non-local exit from the nearest loop block). */
	public static final String RETURN = "return";

	/**
	 * The internal {@code %block} special form establishing the {@code return} boundary
	 * that the loop macros ({@code do}/{@code dolist}/{@code dotimes}/{@code loop}) wrap
	 * their expansion in. Not part of the public Lisp API.
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

	/** The {@code fresh-line} built-in function (newline only if not at line start). */
	public static final String FRESH_LINE = "fresh-line";

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

	/** The {@code read-from-string} built-in function (parse one form from a string). */
	public static final String READ_FROM_STRING = "read-from-string";

	/** The {@code parse-integer} built-in function (parse an integer from a string). */
	public static final String PARSE_INTEGER = "parse-integer";

	// Characters

	/** The {@code char} built-in function (the character at an index of a string). */
	public static final String CHAR = "char";

	/** The {@code schar} built-in function (a synonym for {@code char}). */
	public static final String SCHAR = "schar";

	/** The {@code char-code} built-in function (the code point of a character). */
	public static final String CHAR_CODE = "char-code";

	/**
	 * The {@code code-char} built-in function (the character with a given code point).
	 */
	public static final String CODE_CHAR = "code-char";

	/** The {@code char=} built-in function (character equality). */
	public static final String CHAR_EQ = "char=";

	/** The {@code char<} built-in function (character less-than by code point). */
	public static final String CHAR_LT = "char<";

	/**
	 * The {@code char<=} built-in function (character less-than-or-equal by code point).
	 */
	public static final String CHAR_LE = "char<=";

	/** The {@code char-upcase} built-in function (the uppercase form of a character). */
	public static final String CHAR_UPCASE = "char-upcase";

	/**
	 * The {@code char-downcase} built-in function (the lowercase form of a character).
	 */
	public static final String CHAR_DOWNCASE = "char-downcase";

	/** The {@code characterp} built-in function (true if the argument is a character). */
	public static final String CHARACTERP = "characterp";

	/**
	 * The {@code alpha-char-p} built-in function (true if the character is alphabetic).
	 */
	public static final String ALPHA_CHAR_P = "alpha-char-p";

	/**
	 * The {@code digit-char-p} built-in function (the weight of a digit character in the
	 * given radix, or nil).
	 */
	public static final String DIGIT_CHAR_P = "digit-char-p";

	/** The {@code :radix} keyword recognized by {@code parse-integer}. */
	public static final String RADIX_KEYWORD = ":radix";

	/** The {@code :junk-allowed} keyword recognized by {@code parse-integer}. */
	public static final String JUNK_ALLOWED_KEYWORD = ":junk-allowed";

	/** The {@code :start} keyword recognized by {@code parse-integer}. */
	public static final String START_KEYWORD = ":start";

	/** The {@code :end} keyword recognized by {@code parse-integer}. */
	public static final String END_KEYWORD = ":end";

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

	/** The {@code :initial-value} keyword recognized by {@code reduce}. */
	public static final String INITIAL_VALUE_KEYWORD = ":initial-value";

	/**
	 * The {@code :test} keyword recognized by {@code member} (the equality predicate).
	 */
	public static final String TEST_KEYWORD = ":test";

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

	/**
	 * The {@code fetch} function provided by the {@code rontolisp} package. Starts an
	 * outgoing HTTP request (JavaScript {@code fetch}-style) and immediately returns a
	 * <em>promise</em> (an opaque handle) while the request runs asynchronously. The
	 * optional second argument is an options property list ({@code :method},
	 * {@code :headers}, {@code :body}). The result property list
	 * {@code (:status <int> :body <string> :headers <alist>)} is obtained by passing the
	 * promise to {@code rontolisp:await}.
	 */
	public static final String FETCH = "fetch";

	/**
	 * The {@code await} function provided by the {@code rontolisp} package. Blocks until
	 * the promise returned by {@code rontolisp:fetch} settles and returns the result
	 * property list {@code (:status <int> :body <string> :headers <alist>)}.
	 */
	public static final String AWAIT = "await";

	/** The {@code cl} package name (standard functions, macros and variables). */
	public static final String CL_PKG = "cl";

	/** The {@code cl-user} package name (default working package, uses {@code cl}). */
	public static final String CL_USER_PKG = "cl-user";

	/** The {@code rontolisp} package name (does not use {@code cl}). */
	public static final String RONTOLISP_PKG = "rontolisp";

	/**
	 * The {@code wasm-export} directive provided by the {@code rontolisp} package. Used
	 * as {@code (rontolisp:wasm-export 'name :params '(...) :returns ...)} to mark a
	 * function for direct WASM export. A no-op on the interpreter and the JVM backend.
	 */
	public static final String WASM_EXPORT = "wasm-export";

	/**
	 * The {@code java} package name (interpreter-only Java interop by reflection). It
	 * does not use {@code cl}; its functions wrap arbitrary host objects as
	 * {@code LispJavaObject} and so run on the JVM interpreter only -- the JVM-class and
	 * WASM backends cannot lower a {@code LispJavaObject}.
	 */
	public static final String JAVA_PKG = "java";

	/**
	 * {@code java:new} -- constructs a host object: {@code (java:new "fqcn" args...)}.
	 */
	public static final String JAVA_NEW = "new";

	/** {@code java:call} -- invokes an instance method on a host object. */
	public static final String JAVA_CALL = "call";

	/**
	 * {@code java:static} -- invokes a static method:
	 * {@code (java:static "fqcn" "m" ...)}.
	 */
	public static final String JAVA_STATIC = "static";

	/** {@code java:field} -- reads a static or instance field (e.g. a constant). */
	public static final String JAVA_FIELD = "field";

	/**
	 * {@code java:proxy} -- makes a host interface instance from a rontolisp callable.
	 */
	public static final String JAVA_PROXY = "proxy";

	private LispNames() {
	}

}

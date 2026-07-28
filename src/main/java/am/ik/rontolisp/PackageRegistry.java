package am.ik.rontolisp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A mutable registry of {@link LispPackage packages}, seeded with the built-in packages
 * ({@code cl}, {@code cl-user}, {@code rontolisp}, and the interpreter-only {@code java}
 * interop package). The resolution rules in {@link PackageResolver} operate generically
 * over this registry, so a new package (or a future {@code defpackage}) only needs to be
 * registered here.
 */
public final class PackageRegistry {

	/**
	 * The {@code cl} special forms: operators handled natively by the evaluator and the
	 * compilers that have no function value ({@code #'if} is an error).
	 */
	private static final Set<String> CL_SPECIAL_FORMS = Set.of(LispNames.QUOTE, LispNames.IF, LispNames.LET,
			LispNames.PROGN, LispNames.SETQ, LispNames.LAMBDA, LispNames.WHILE, LispNames.FUNCTION, LispNames.DEFUN,
			LispNames.DEFMACRO, LispNames.DEFSTRUCT, LispNames.DEFCLASS, LispNames.DEFGENERIC, LispNames.DEFMETHOD,
			LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT, LispNames.RETURN, LispNames.IN_PACKAGE,
			LispNames.DEFPACKAGE, LispNames.PROGV, LispNames.UNWIND_PROTECT, LispNames.TAGBODY, LispNames.GO);

	/**
	 * The {@code cl} macros: operators expanded by {@link LispMacroExpander} that have no
	 * function value. Names that expand internally but are also usable as function values
	 * ({@code first}, {@code length}, {@code 1+}, ...) are classified as functions.
	 */
	private static final Set<String> CL_MACROS = Set.of(LispNames.BLOCK, LispNames.COND, LispNames.CASE, LispNames.AND,
			LispNames.OR, LispNames.WHEN, LispNames.UNLESS, LispNames.DOTIMES, LispNames.SETF, LispNames.PUSH,
			LispNames.POP, LispNames.REMF, LispNames.LET_STAR, LispNames.DOLIST, LispNames.INCF, LispNames.DECF,
			LispNames.FORMAT, LispNames.WITH_OPEN_FILE, LispNames.WITH_OPEN_STREAM, LispNames.PROG1, LispNames.DO,
			LispNames.DO_STAR, LispNames.PROG2, LispNames.PSETQ, LispNames.PSETF, LispNames.TYPECASE, LispNames.ECASE,
			LispNames.ETYPECASE, LispNames.CCASE, LispNames.ERROR, LispNames.CERROR, LispNames.TIME, LispNames.LOOP,
			LispNames.CHECK_TYPE, LispNames.ASSERT, LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM,
			LispNames.THE, LispNames.EVAL_WHEN, LispNames.LOCALLY, LispNames.FLET, LispNames.LABELS,
			LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_LIST, LispNames.MULTIPLE_VALUE_CALL,
			LispNames.NTH_VALUE, LispNames.MULTIPLE_VALUE_SETQ, LispNames.MULTIPLE_VALUE_PROG1, LispNames.ROTATEF,
			LispNames.DESTRUCTURING_BIND, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING,
			LispNames.WITH_STANDARD_IO_SYNTAX, LispNames.PUSHNEW, LispNames.DEFTYPE, LispNames.DEFINE_CONDITION,
			LispNames.DEFINE_MODIFY_MACRO, LispNames.DEFINE_SETF_EXPANDER, LispNames.DEFSETF,
			LispNames.DEFINE_COMPILER_MACRO, LispNames.RESTART_CASE, LispNames.MACROLET, LispNames.MAKE_CONDITION,
			LispNames.DOCUMENTATION, LispNames.COMPLEMENT, LispNames.COMPLEX, LispNames.WARN, LispNames.SIGNAL,
			LispNames.RETURN_FROM, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.WITH_SLOTS,
			LispNames.HANDLER_CASE, LispNames.IGNORE_ERRORS, LispNames.HANDLER_BIND, LispNames.WRITE_CHAR,
			LispNames.MAKE_SEQUENCE, LispNames.PROG, LispNames.PROG_STAR, LispNames.SHIFTF, LispNames.LOAD_TIME_VALUE,
			LispNames.TYPEP, LispNames.SLOT_BOUNDP, LispNames.SLOT_MAKUNBOUND, LispNames.PRINT_UNREADABLE_OBJECT,
			LispNames.WITH_PACKAGE_ITERATOR, LispNames.DO_EXTERNAL_SYMBOLS);

	/**
	 * The {@code cl} functions: every standard name usable as a function value via
	 * {@code #'name}. Car/cdr compositions ({@code cadr}, {@code cddr}, ...) are
	 * recognized separately by {@link LispMacroExpander#isCarCdrComposition} and are not
	 * enumerated here.
	 */
	private static final Set<String> CL_FUNCTIONS = Set.of(LispNames.FUNCALL, LispNames.ADD, LispNames.SUB,
			LispNames.MUL, LispNames.DIV, LispNames.MOD, LispNames.REM, LispNames.ABS, LispNames.MIN, LispNames.MAX,
			LispNames.SQRT, LispNames.ISQRT, LispNames.EXPT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS,
			LispNames.TAN, LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
			LispNames.TANH, LispNames.RANDOM, LispNames.GCD, LispNames.LCM, LispNames.SIGNUM, LispNames.EQ,
			LispNames.EQ_GENERAL, LispNames.EQL, LispNames.EQUAL, LispNames.EQUALP, LispNames.LT, LispNames.GT,
			LispNames.LE, LispNames.GE, LispNames.CONS, LispNames.CAR, LispNames.CDR, LispNames.LIST, LispNames.APPEND,
			LispNames.NTHCDR, LispNames.RPLACA, LispNames.RPLACD, LispNames.MAPCAR, LispNames.MAP, LispNames.MAP_INTO,
			LispNames.MAPC, LispNames.MAPCAN, LispNames.APPLY, LispNames.SORT, LispNames.REDUCE, LispNames.EVERY,
			LispNames.SOME, LispNames.REMOVE, LispNames.REMOVE_IF, LispNames.REMOVE_IF_NOT, LispNames.NOT,
			LispNames.NULL, LispNames.ATOM, LispNames.NUMBERP, LispNames.INTEGERP, LispNames.FLOATP,
			LispNames.RATIONALP, LispNames.NUMERATOR, LispNames.DENOMINATOR, LispNames.SYMBOLP, LispNames.STRINGP,
			LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP, LispNames.FLOAT, LispNames.TRUNCATE, LispNames.FLOOR,
			LispNames.CEILING, LispNames.ROUND, LispNames.ONE_PLUS, LispNames.ONE_MINUS, LispNames.ZEROP,
			LispNames.PLUSP, LispNames.MINUSP, LispNames.EVENP, LispNames.ODDP, LispNames.FIRST, LispNames.SECOND,
			LispNames.THIRD, LispNames.FOURTH, LispNames.NTH, LispNames.PRINT, LispNames.PRIN1, LispNames.PRINC,
			LispNames.TERPRI, LispNames.FRESH_LINE, LispNames.READ_LINE, LispNames.READ, LispNames.EVAL, LispNames.LOAD,
			LispNames.REQUIRE, LispNames.PROVIDE, LispNames.SYMBOL_FUNCTION, LispNames.LENGTH, LispNames.REVERSE,
			LispNames.MEMBER, LispNames.FIND, LispNames.FIND_IF, LispNames.FIND_IF_NOT, LispNames.MEMBER_IF,
			LispNames.POSITION, LispNames.POSITION_IF, LispNames.POSITION_IF_NOT, LispNames.COUNT, LispNames.COUNT_IF,
			LispNames.ASSOC, LispNames.ASSOC_IF, LispNames.LAST, LispNames.BUTLAST, LispNames.GETF,
			LispNames.REMOVE_DUPLICATES, LispNames.NCONC, LispNames.REST, LispNames.PRINC_TO_STRING,
			LispNames.PRIN1_TO_STRING, LispNames.CONCATENATE, LispNames.STRING, LispNames.STRING_UPCASE,
			LispNames.STRING_DOWNCASE, LispNames.STRING_CAPITALIZE, LispNames.SUBSEQ, LispNames.STRING_EQ,
			LispNames.STRING_LT, LispNames.STRING_GT, LispNames.STRING_LE, LispNames.STRING_GE, LispNames.STRING_NE,
			LispNames.STRING_EQUAL, LispNames.STRING_LESSP, LispNames.STRING_GREATERP, LispNames.STRING_NOT_GREATERP,
			LispNames.STRING_NOT_LESSP, LispNames.STRING_NOT_EQUAL, LispNames.STRING_TRIM, LispNames.STRING_LEFT_TRIM,
			LispNames.STRING_RIGHT_TRIM, LispNames.OPEN, LispNames.CLOSE, LispNames.PROBE_FILE, LispNames.WRITE_LINE,
			LispNames.READ_BYTE, LispNames.WRITE_BYTE, LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE,
			LispNames.IDENTITY, LispNames.COPY_LIST, LispNames.COPY_TREE, LispNames.NREVERSE, LispNames.MAKE_LIST,
			LispNames.UNION, LispNames.INTERSECTION, LispNames.SET_DIFFERENCE, LispNames.ADJOIN, LispNames.LOGAND,
			LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.LOGANDC1, LispNames.LOGANDC2,
			LispNames.LOGORC1, LispNames.LOGORC2, LispNames.ASH, LispNames.INTEGER_LENGTH, LispNames.LOGBITP,
			LispNames.BYTE, LispNames.BYTE_SIZE, LispNames.BYTE_POSITION, LispNames.LDB, LispNames.DPB,
			LispNames.LIST_STAR, LispNames.ACONS, LispNames.ENDP, LispNames.ELT, LispNames.RASSOC, LispNames.PAIRLIS,
			LispNames.COPY_ALIST, LispNames.REVAPPEND, LispNames.NRECONC, LispNames.MAPLIST, LispNames.MAPCON,
			LispNames.MAPL, LispNames.NOTANY, LispNames.NOTEVERY, LispNames.DELETE, LispNames.DELETE_IF,
			LispNames.DELETE_IF_NOT, LispNames.SUBSTITUTE, LispNames.SUBST, LispNames.NSUBSTITUTE, LispNames.SEARCH,
			LispNames.MISMATCH, LispNames.GET_UNIVERSAL_TIME, LispNames.ENCODE_UNIVERSAL_TIME,
			LispNames.DECODE_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME,
			LispNames.FORCE_OUTPUT, LispNames.FINISH_OUTPUT, LispNames.LISTEN, LispNames.GETENV,
			LispNames.READ_FROM_STRING, LispNames.PARSE_INTEGER, LispNames.CHAR, LispNames.SCHAR, LispNames.CHAR_CODE,
			LispNames.CODE_CHAR, LispNames.CHAR_EQ, LispNames.CHAR_LT, LispNames.CHAR_LE, LispNames.CHAR_GT,
			LispNames.CHAR_GE, LispNames.CHAR_NE, LispNames.CHAR_EQUAL, LispNames.CHAR_UPCASE, LispNames.CHAR_DOWNCASE,
			LispNames.CHARACTERP, LispNames.ALPHA_CHAR_P, LispNames.ALPHANUMERICP,
			LispNames.MAKE_LOAD_FORM_SAVING_SLOTS, LispNames.SXHASH, LispNames.SBIT, LispNames.BIT,
			LispNames.BOTH_CASE_P, LispNames.SPECIAL_OPERATOR_P, LispNames.MACRO_FUNCTION,
			LispNames.COMPILED_FUNCTION_P, LispNames.FUNCTION_LAMBDA_EXPRESSION, LispNames.LIST_ALL_PACKAGES,
			LispNames.FIND_CLASS, LispNames.GET, LispNames.DIGIT_CHAR_P, LispNames.DIGIT_CHAR,
			LispNames.MAKE_HASH_TABLE, LispNames.GETHASH, LispNames.REMHASH, LispNames.CLRHASH,
			LispNames.HASH_TABLE_COUNT, LispNames.HASH_TABLE_TEST, LispNames.HASH_TABLE_SIZE,
			LispNames.HASH_TABLE_REHASH_SIZE, LispNames.HASH_TABLE_REHASH_THRESHOLD, LispNames.HASH_TABLE_P,
			LispNames.MAPHASH, LispNames.MAKE_ARRAY, LispNames.AREF, LispNames.VECTOR, LispNames.SVREF,
			LispNames.ARRAY_DIMENSIONS, LispNames.ARRAY_DIMENSION, LispNames.ARRAY_RANK, LispNames.ARRAY_TOTAL_SIZE,
			LispNames.ROW_MAJOR_AREF, LispNames.ARRAY_ROW_MAJOR_INDEX, LispNames.COERCE, LispNames.GENSYM,
			LispNames.MACROEXPAND, LispNames.MACROEXPAND_1, LispNames.VALUES, LispNames.WRITE_STRING,
			LispNames.WRITE_TO_STRING, LispNames.SYMBOL_NAME, LispNames.INTERN, LispNames.FIND_SYMBOL,
			LispNames.MAKE_SYMBOL, LispNames.BOUNDP, LispNames.FBOUNDP, LispNames.SYMBOL_VALUE, LispNames.FUNCTIONP,
			LispNames.VALUES_LIST, LispNames.NE, LispNames.FILL_POINTER, LispNames.ARRAY_HAS_FILL_POINTER_P,
			LispNames.ADJUSTABLE_ARRAY_P, LispNames.VECTOR_PUSH, LispNames.VECTOR_POP, LispNames.VECTOR_PUSH_EXTEND,
			LispNames.ARRAY_ELEMENT_TYPE, LispNames.ADJUST_ARRAY, LispNames.ARRAY_DISPLACEMENT, LispNames.STABLE_SORT,
			LispNames.COPY_SEQ, LispNames.READ_CHAR, LispNames.VECTORP, LispNames.ARRAYP, LispNames.MAKE_STRING,
			LispNames.REPLACE, LispNames.LOWER_CASE_P, LispNames.UPPER_CASE_P, LispNames.CONSTANTP,
			LispNames.GET_SETF_EXPANSION, LispNames.STREAMP, LispNames.SIMPLE_STRING_P, LispNames.MASK_FIELD,
			LispNames.SCALE_FLOAT, LispNames.DECODE_FLOAT, LispNames.SUBTYPEP, LispNames.CHAR_NAME,
			LispNames.FDEFINITION, LispNames.FILE_POSITION, LispNames.FILE_LENGTH, LispNames.MAKE_BROADCAST_STREAM,
			LispNames.PATHNAMEP, LispNames.INPUT_STREAM_P, LispNames.OUTPUT_STREAM_P, LispNames.OPEN_STREAM_P,
			LispNames.STREAM_ELEMENT_TYPE, LispNames.CLASS_OF, LispNames.SIMPLE_CONDITION_FORMAT_CONTROL,
			LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS, LispNames.MAKE_PATHNAME, LispNames.COPY_READTABLE,
			LispNames.SET_DISPATCH_MACRO_CHARACTER, LispNames.FIND_PACKAGE, LispNames.SYMBOL_PACKAGE,
			LispNames.TYPE_OF);

	/** The {@code cl} variables. */
	private static final Set<String> CL_VARIABLES = Set.of(LispNames.PACKAGE_VAR, LispNames.READ_DEFAULT_FLOAT_FORMAT,
			LispNames.ARRAY_DIMENSION_LIMIT, LispNames.ARRAY_TOTAL_SIZE_LIMIT, LispNames.CHAR_CODE_LIMIT,
			LispNames.INTERNAL_TIME_UNITS_PER_SECOND, LispNames.PRINT_CIRCLE_VAR, LispNames.FEATURES_VAR,
			LispNames.STANDARD_OUTPUT_VAR, LispNames.ERROR_OUTPUT_VAR, LispNames.READTABLE_VAR,
			LispNames.LAMBDA_LIST_KEYWORDS);

	/**
	 * The {@code cl} type-specifier (and clause-keyword) names that are not also
	 * function/macro names. Registered so they resolve bare inside user packages (a
	 * {@code (:use :cl)} package's {@code 'double-float} or {@code (integer 0)} must not
	 * become {@code pkg::double-float}); they are not callable and do not appear in the
	 * introspection listings.
	 */
	private static final Set<String> CL_TYPES = Set.of("INTEGER", "NUMBER", "RATIONAL", "RATIO", "REAL", "FIXNUM",
			"BIGNUM", "SINGLE-FLOAT", "DOUBLE-FLOAT", "SHORT-FLOAT", "LONG-FLOAT", "UNSIGNED-BYTE", "SIGNED-BYTE",
			"BOOLEAN", "SEQUENCE", "ARRAY", "SIMPLE-ARRAY", "SIMPLE-VECTOR", "SIMPLE-STRING", "BASE-STRING",
			"CHARACTER", "BASE-CHAR", "STANDARD-CHAR", "SATISFIES", "OTHERWISE", "STREAM");

	/**
	 * Internal {@code %}-prefixed helpers owned by {@code cl} but excluded from the
	 * introspection listings.
	 */
	private static final Set<String> CL_INTERNALS = Set.of(LispNames.REMF_TAIL, LispNames.STRING_CONCAT,
			LispNames.BLOCK_INTERNAL, LispNames.FN_BLOCK_INTERNAL, LispNames.ERROR_INTERNAL, LispNames.PUTHASH,
			LispNames.ASET, LispNames.ROW_MAJOR_ASET, LispNames.MAKE_STRING_OUTPUT_STREAM,
			LispNames.MAKE_STRING_INPUT_STREAM, LispNames.STRING_STREAM_CONTENTS, LispNames.ARRAYP_INTERNAL,
			LispNames.MV_SPILL, LispNames.SET_FILL_POINTER, LispNames.ARRAY_BECOME, LispNames.ARRAY_ALIKE,
			LispNames.ARRAY_DISP_TARGET, LispNames.ARRAY_DISP_OFFSET, LispNames.WARN_INTERNAL, LispNames.SCHAR_SET,
			LispNames.IEEE754_DOUBLE_BITS, LispNames.IEEE754_DOUBLE_FROM_BITS, LispNames.IEEE754_SINGLE_BITS,
			LispNames.IEEE754_SINGLE_FROM_BITS, LispNames.READ_EVAL, LispNames.READ_EVAL_TEMPLATE,
			LispNames.SUBSEQ_CORE, LispNames.NLX_TAG_INTERNAL, LispNames.NLX_CATCH_INTERNAL,
			LispNames.NLX_THROW_INTERNAL, LispNames.STRING_COMPARE, LispNames.OBJ_NEW, LispNames.OBJ_REF,
			LispNames.OBJ_SET, LispNames.OBJ_IS, LispNames.OBJ_TAG, LispNames.OBJ_P, LispNames.OBJ_SLOTS);

	/**
	 * The names of the symbols owned by the {@code cl} package, derived as the union of
	 * the categorized sets above.
	 */
	private static final Set<String> CL_SYMBOLS = union(CL_SPECIAL_FORMS, CL_MACROS, CL_FUNCTIONS, CL_VARIABLES,
			CL_INTERNALS, CL_TYPES);

	/**
	 * The exported {@code cl} symbols: everything but the {@code %}-prefixed internals
	 * (car/cdr compositions are recognized separately by
	 * {@link LispMacroExpander#isCarCdrComposition} and are also external).
	 */
	private static final Set<String> CL_EXTERNALS = union(CL_SPECIAL_FORMS, CL_MACROS, CL_FUNCTIONS, CL_VARIABLES,
			CL_TYPES);

	/**
	 * The functions exported by the {@code linalg} package (numpy-style vector/matrix
	 * operations), implemented in {@code linalg.lisp} (see {@code LinalgLibrary}). The
	 * names are plain strings rather than {@link LispNames} constants because they exist
	 * only as Lisp-source defuns -- no evaluator or compiler dispatches on them.
	 */
	private static final Set<String> LINALG_FUNCTIONS = Set.of("ZEROS", "ONES", "FULL", "EYE", "ARANGE", "LINSPACE",
			"FROM-LIST", "TO-LIST", "SHAPE", "NDIM", "SIZE", "RESHAPE", "FLATTEN", "TRANSPOSE", "PAD", "ADD", "SUB",
			"MUL", "DIV", "EMAP", "DOT", "MATMUL", "OUTER", "SUM", "MEAN", "AMAX", "AMIN", "ARGMAX", "ARGMIN", "NORM",
			"TRACE", "DET", "INV", "SOLVE", "ARRAY-EQUAL", "EXP", "LOG", "TANH", "SIN", "COS", "TAN", "ASIN", "ACOS",
			"ATAN", "SINH", "COSH", "SQRT", "ABS", "SQUARE", "NEGATIVE", "SIGN", "RECIPROCAL", "MAXIMUM", "MINIMUM",
			"CLIP", "RELU", "DIFF", "GRADIENT", "ZEROS-LIKE", "SEED", "RAND", "RANDN", "UNIFORM", "CHOICE",
			"PERMUTATION", "TAKE-ROWS", "ROW", "GATHER", "ONE-HOT", "EQUAL", "GREATER", "GREATER-EQUAL", "LESS",
			"LESS-EQUAL");

	private static final List<String> LINALG_FUNCTION_NAMES = sorted(LINALG_FUNCTIONS);

	/**
	 * The functions exported by the {@code vec} package (portable packed-{@code f64}
	 * vector kernels over the packed {@code double-float} array type). Implemented once
	 * in rontolisp itself ({@code vec.lisp}, see {@code VecLibrary}) as the scalar
	 * reference, exactly like {@code linalg}; the JVM {@code --simd} flag and the
	 * {@code --no-gc} scalar backend additionally intercept the vectorizable kernels for
	 * acceleration.
	 */
	private static final Set<String> VEC_FUNCTIONS = Set.of(LispNames.VEC_ZEROS, LispNames.VEC_ONES,
			LispNames.VEC_ARANGE, LispNames.VEC_FROM_LIST, LispNames.VEC_TO_LIST, LispNames.VEC_AREF,
			LispNames.VEC_ASET, LispNames.VEC_LENGTH, LispNames.VEC_ADD, LispNames.VEC_SUB, LispNames.VEC_MUL,
			LispNames.VEC_SCALE, LispNames.VEC_SUM, LispNames.VEC_MEAN, LispNames.VEC_DOT, LispNames.VEC_NORM,
			LispNames.VEC_MATVEC, LispNames.VEC_ADD_INTO, LispNames.VEC_SUB_INTO, LispNames.VEC_MUL_INTO,
			LispNames.VEC_SCALE_INTO, LispNames.VEC_MATVEC_INTO, LispNames.VEC_EXP, LispNames.VEC_LOG,
			LispNames.VEC_TANH, LispNames.VEC_SIN, LispNames.VEC_COS, LispNames.VEC_TAN, LispNames.VEC_ASIN,
			LispNames.VEC_ACOS, LispNames.VEC_ATAN, LispNames.VEC_SINH, LispNames.VEC_COSH, LispNames.VEC_SQRT,
			LispNames.VEC_ABS, LispNames.VEC_SQUARE, LispNames.VEC_NEGATIVE, LispNames.VEC_SIGN,
			LispNames.VEC_RECIPROCAL, LispNames.VEC_EXP_INTO, LispNames.VEC_LOG_INTO, LispNames.VEC_TANH_INTO,
			LispNames.VEC_SIN_INTO, LispNames.VEC_COS_INTO, LispNames.VEC_TAN_INTO, LispNames.VEC_ASIN_INTO,
			LispNames.VEC_ACOS_INTO, LispNames.VEC_ATAN_INTO, LispNames.VEC_SINH_INTO, LispNames.VEC_COSH_INTO,
			LispNames.VEC_SQRT_INTO, LispNames.VEC_ABS_INTO, LispNames.VEC_SQUARE_INTO, LispNames.VEC_NEGATIVE_INTO,
			LispNames.VEC_SIGN_INTO, LispNames.VEC_RECIPROCAL_INTO, LispNames.VEC_MAXIMUM, LispNames.VEC_MINIMUM,
			LispNames.VEC_RELU, LispNames.VEC_CLIP, LispNames.VEC_MAXIMUM_INTO, LispNames.VEC_MINIMUM_INTO,
			LispNames.VEC_RELU_INTO, LispNames.VEC_CLIP_INTO);

	private static final List<String> VEC_FUNCTION_NAMES = sorted(VEC_FUNCTIONS);

	/**
	 * The functions exported by the {@code usocket} package (a usocket-compatible shim
	 * over the {@code rontolisp:tcp-*} built-ins), implemented in {@code usocket.lisp}
	 * (see {@code UsocketLibrary}). Plain strings, like {@code linalg}: no evaluator or
	 * compiler dispatches on them (only the {@code with-*} macros below are dispatched
	 * on, by their qualified names).
	 */
	private static final Set<String> USOCKET_FUNCTIONS = Set.of(LispNames.USOCKET_SOCKET_CONNECT, "SOCKET-LISTEN",
			"SOCKET-ACCEPT", "SOCKET-CLOSE", "SOCKET-STREAM", "GET-LOCAL-PORT", "GET-LOCAL-ADDRESS", "GET-LOCAL-NAME",
			"GET-PEER-PORT", "GET-PEER-ADDRESS", "GET-PEER-NAME");

	/**
	 * The macros exported by the {@code usocket} package: built-in
	 * {@code LispMacroExpander} expansions dispatched on their qualified names (the
	 * {@code rontolisp:with-arena} pattern), not {@code usocket.lisp} defuns.
	 */
	private static final Set<String> USOCKET_MACROS = Set.of(LispNames.USOCKET_WITH_CLIENT_SOCKET,
			LispNames.USOCKET_WITH_CONNECTED_SOCKET, LispNames.USOCKET_WITH_SERVER_SOCKET,
			LispNames.USOCKET_WITH_SOCKET_LISTENER);

	/**
	 * The variables exported by the {@code usocket} package ({@code usocket.lisp}
	 * defparameters), plus the usocket condition-type names. rontolisp has no condition
	 * system, so the condition names resolve as plain data symbols only (e.g.
	 * {@code 'usocket:socket-error}) -- {@code handler-case} over them is not supported.
	 */
	private static final Set<String> USOCKET_VARIABLES = Set.of("*WILDCARD-HOST*", "*AUTO-PORT*", "SOCKET-CONDITION",
			"SOCKET-ERROR", "CONNECTION-REFUSED-ERROR", "CONNECTION-ABORTED-ERROR", "CONNECTION-RESET-ERROR",
			"TIMEOUT-ERROR", "ADDRESS-IN-USE-ERROR", "NS-ERROR");

	private static final Set<String> USOCKET_EXTERNALS = union(USOCKET_FUNCTIONS, USOCKET_MACROS, USOCKET_VARIABLES);

	private static final List<String> USOCKET_FUNCTION_NAMES = sorted(USOCKET_FUNCTIONS);

	private static final List<String> CL_FUNCTION_NAMES = sorted(CL_FUNCTIONS);

	private static final List<String> CL_MACRO_NAMES = sorted(CL_MACROS);

	private static final List<String> CL_SPECIAL_FORM_NAMES = sorted(CL_SPECIAL_FORMS);

	private static final Set<String> SPECIAL_OPERATOR_NAMES = union(CL_SPECIAL_FORMS, CL_MACROS);

	private final Map<String, LispPackage> packages = new HashMap<>();

	/**
	 * The built-in package nicknames, mapping each nickname to the canonical package
	 * name: the standard Common Lisp names ({@code common-lisp} for {@code cl},
	 * {@code common-lisp-user} for {@code cl-user}) so portable {@code (:use
	 * #:common-lisp)} clauses resolve, plus the shorthands {@code rl} for
	 * {@code rontolisp}, {@code la} for {@code linalg} and {@code quicklisp} for
	 * {@code ql}. Static (unlike user {@code defpackage :nicknames}) so
	 * {@link #splitQualified} can normalize built-in qualifiers for the compile-path
	 * pre-passes that scan the program before package resolution runs.
	 */
	private static final Map<String, String> BUILTIN_NICKNAMES = Map.of("COMMON-LISP", LispNames.CL_PKG,
			"COMMON-LISP-USER", LispNames.CL_USER_PKG, "RL", LispNames.RONTOLISP_PKG, "LA", LispNames.LINALG_PKG,
			"QUICKLISP", LispNames.QL_PKG, "C2MOP", LispNames.CLOSER_MOP_PKG, "C2CL", LispNames.CLOSER_MOP_PKG,
			"FLOAT-FEATURES", LispNames.FLOAT_FEATURES_PKG, "BT", LispNames.BORDEAUX_THREADS_PKG);

	/**
	 * Package nicknames, mapping each nickname to the canonical package name. Seeded with
	 * {@link #BUILTIN_NICKNAMES}; {@code defpackage :nicknames} adds more.
	 */
	private final Map<String, String> nicknames = new HashMap<>(BUILTIN_NICKNAMES);

	/**
	 * The canonical names of the packages the constructor seeds (plus {@code keyword},
	 * the designator of the keyword package accepted by {@code intern}). Kept in sync
	 * with the constructor by hand; used by {@link #isBuiltinPackageName} for the upcase
	 * reader mode's canonical fold, which must not depend on a registry instance.
	 */
	private static final Set<String> BUILTIN_PACKAGE_NAMES = Set.of(LispNames.CL_PKG, LispNames.CL_USER_PKG,
			LispNames.RONTOLISP_PKG, LispNames.LINALG_PKG, LispNames.VEC_PKG, LispNames.USOCKET_PKG, LispNames.JAVA_PKG,
			LispNames.ASDF_PKG, LispNames.QL_PKG, LispNames.UIOP_PKG, LispNames.CLOSER_MOP_PKG,
			LispNames.FLEXI_STREAMS_PKG, LispNames.FLOAT_FEATURES_PKG, LispNames.TRIVIAL_GRAY_STREAMS_PKG,
			LispNames.BORDEAUX_THREADS_PKG, "KEYWORD");

	/**
	 * Creates a registry seeded with the built-in packages.
	 */
	public PackageRegistry() {
		define(new LispPackage(LispNames.CL_PKG, List.of(), CL_SYMBOLS, CL_EXTERNALS));
		// cl-user exports nothing, like the Common Lisp COMMON-LISP-USER package: its
		// symbols are reachable as cl-user::name, never cl-user:name.
		define(new LispPackage(LispNames.CL_USER_PKG, List.of(LispNames.CL_PKG), new HashSet<>(), Set.of()));
		// Its canonical spelling is rontolisp; rl is a built-in nickname.
		define(new LispPackage(LispNames.RONTOLISP_PKG, List.of(), new HashSet<>(Set.of(LispNames.VERSION,
				LispNames.LIST_FUNCTIONS, LispNames.LIST_MACROS, LispNames.LIST_SPECIAL_FORMS, LispNames.FETCH,
				LispNames.AWAIT, LispNames.ASYNC, LispNames.ASYNC_DEFUN, LispNames.ASYNC_LAMBDA, LispNames.FUTUREP,
				LispNames.ASYNC_STREAMP, LispNames.MAKE_STREAM, LispNames.STREAM_READ, LispNames.STREAM_WRITE,
				LispNames.STREAM_CLOSE, LispNames.READ_ALL, LispNames.WAIT_FOR, LispNames.THEN, LispNames.THEN_STAR,
				LispNames.CATCH, LispNames.FINALLY, LispNames.JSON_PARSE, LispNames.JSON_STRINGIFY,
				LispNames.PLIST_HASH_TABLE, LispNames.HASH_TABLE_PLIST, LispNames.ALIST_HASH_TABLE,
				LispNames.HASH_TABLE_ALIST, LispNames.URL_DECODE, LispNames.URL_ENCODE, LispNames.QUERY_PARAMS,
				LispNames.QUERY_PARAM, LispNames.URL_PATH, LispNames.URL_QUERY, LispNames.WASM_EXPORT,
				LispNames.WASM_IMPORT, LispNames.WIT_EXPORT, LispNames.WIT_IMPORT, LispNames.WIT_PROVIDE,
				LispNames.WIT_ERROR, LispNames.WIT_ERROR_PAYLOAD, LispNames.WITH_ARENA, LispNames.MAKE_MUTEX,
				LispNames.MUTEX_ACQUIRE, LispNames.MUTEX_RELEASE, LispNames.WITH_MUTEX, LispNames.HTTP_HANDLER,
				LispNames.TCP_CONNECT, LispNames.TCP_LISTEN, LispNames.TCP_ACCEPT, LispNames.TCP_LOCAL_PORT,
				LispNames.TCP_LOCAL_ADDRESS, LispNames.TCP_PEER_ADDRESS, LispNames.TCP_PEER_PORT, LispNames.TLS_CONNECT,
				LispNames.TLS_LISTEN, LispNames.TLS_LISTEN_PEM, LispNames.TLS_LISTEN_P12, LispNames.RANDOM_BYTES,
				// rontolisp's own Gray-stream extension
				// (eval.GrayStreamsLibrary).
				LispNames.GRAY_CHAR_OUTPUT_STREAM, LispNames.GRAY_CHAR_INPUT_STREAM, LispNames.GRAY_STREAM_WRITE_CHAR,
				LispNames.GRAY_STREAM_WRITE_STRING))));
		// numpy-style vector/matrix operations, implemented once in linalg.lisp and
		// spliced/loaded on demand (LinalgLibrary). Does not use cl; every function
		// is external. Its canonical spelling is linalg; la is a built-in nickname.
		define(new LispPackage(LispNames.LINALG_PKG, List.of(), new HashSet<>(LINALG_FUNCTIONS)));
		// Portable packed-f64 vector kernels, implemented once in vec.lisp (VecLibrary)
		// as the scalar reference and spliced/loaded on demand like linalg. The JVM
		// --simd
		// flag and the --no-gc scalar backend accelerate the vectorizable kernels. Does
		// not
		// use cl; every function is external.
		define(new LispPackage(LispNames.VEC_PKG, List.of(), new HashSet<>(VEC_FUNCTIONS)));
		// A usocket-compatible shim over the rontolisp:tcp-* built-ins, implemented once
		// in usocket.lisp (UsocketLibrary) and spliced/loaded on demand like linalg; the
		// with-* macros are built-in LispMacroExpander expansions. Does not use cl; every
		// registered symbol is external.
		define(new LispPackage(LispNames.USOCKET_PKG, List.of(), new HashSet<>(USOCKET_EXTERNALS)));
		// Interpreter-only Java interop. Does not use cl; its values (LispJavaObject)
		// run on the JVM interpreter only -- the compilers cannot lower them.
		define(new LispPackage(LispNames.JAVA_PKG, List.of(), new HashSet<>(Set.of(LispNames.JAVA_NEW,
				LispNames.JAVA_CALL, LispNames.JAVA_STATIC, LispNames.JAVA_FIELD, LispNames.JAVA_PROXY))));
		// A limited, API-compatible subset of ASDF (system definitions parsed from .asd
		// files as plain data -- see eval.AsdfSystems). Does not use cl; both symbols
		// are external.
		define(new LispPackage(LispNames.ASDF_PKG, List.of(), new HashSet<>(Set.of(LispNames.DEFSYSTEM,
				LispNames.LOAD_SYSTEM, LispNames.FIND_SYSTEM, LispNames.SYSTEM_SOURCE_DIRECTORY))));
		// A limited, API-compatible subset of Quicklisp: ql:quickload downloads a system
		// (and its dependencies) from the real Quicklisp distribution into a local cache
		// and then defers to the asdf subset (see eval.QuicklispClient). Its canonical
		// spelling is ql; quicklisp is a built-in nickname. Does not use cl; the symbol
		// is external.
		define(new LispPackage(LispNames.QL_PKG, List.of(), new HashSet<>(Set.of(LispNames.QUICKLOAD))));
		// A stub of ASDF's uiop utility package: real libraries name it in
		// (:import-from #:uiop) clauses and call it on platform-only paths (e.g.
		// uiop:native-namestring on a pathname branch). add-package-local-nickname is
		// the one function with a real definition (LispEvaluator, lite: a GLOBAL
		// nickname); the rest resolve but are undefined-function errors when called.
		Set<String> uiopExternals = Set.of(LispNames.NATIVE_NAMESTRING, LispNames.NAMESTRING, LispNames.GETENV,
				LispNames.OS_UNIX_P, LispNames.OS_MACOSX_P, LispNames.ADD_PACKAGE_LOCAL_NICKNAME,
				LispNames.MERGE_PATHNAMES_STAR, LispNames.FILE_EXISTS_P, LispNames.RUN_PROGRAM);
		Set<String> uiopSymbols = new HashSet<>(uiopExternals);
		// Internal in real UIOP too: every call site spells it
		// uiop::get-pathname-defaults. Owned by the package rather than reached by
		// the resolver's tolerance for an unknown :: member.
		uiopSymbols.add(LispNames.GET_PATHNAME_DEFAULTS);
		define(new LispPackage(LispNames.UIOP_PKG, List.of(), uiopSymbols, uiopExternals));
		// The dependency-shim packages behind the built-in ASDF systems of the same
		// names (see eval.ShimLibraries): closer-mop (nicknames c2mop/c2cl),
		// flexi-streams, org.shirakumo.float-features (nickname float-features) and
		// trivial-gray-streams.
		define(new LispPackage(LispNames.CLOSER_MOP_PKG, List.of(), new HashSet<>(Set.of(LispNames.CLASS_SLOTS,
				LispNames.ENSURE_FINALIZED, LispNames.SLOT_DEFINITION_NAME, LispNames.SLOT_DEFINITION_TYPE))));
		define(new LispPackage(LispNames.FLEXI_STREAMS_PKG, List.of(), new HashSet<>(
				Set.of(LispNames.MAKE_FLEXI_STREAM, LispNames.STRING_TO_OCTETS, LispNames.OCTETS_TO_STRING))));
		define(new LispPackage(LispNames.FLOAT_FEATURES_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.BITS_DOUBLE_FLOAT, LispNames.DOUBLE_FLOAT_BITS,
						LispNames.SINGLE_FLOAT_BITS, LispNames.BITS_SINGLE_FLOAT))));
		define(new LispPackage(LispNames.TRIVIAL_GRAY_STREAMS_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.GRAY_CHAR_OUTPUT_STREAM, LispNames.GRAY_CHAR_INPUT_STREAM,
						LispNames.GRAY_STREAM_WRITE_CHAR, LispNames.GRAY_STREAM_WRITE_STRING))));
		// bordeaux-threads (nickname bt): the locking subset over the rontolisp:*-mutex
		// primitives, implemented in bordeaux-threads.lisp (eval.ShimLibraries) except
		// with-lock-held, which is a built-in LispMacroExpander expansion dispatched on
		// its qualified name (the usocket:with-* pattern). Thread CREATION is out: no
		// backend can spawn one from Lisp, and a library that asked would be broken by a
		// shim that pretended otherwise.
		define(new LispPackage(LispNames.BORDEAUX_THREADS_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.MAKE_LOCK, LispNames.ACQUIRE_LOCK, LispNames.RELEASE_LOCK,
						LispNames.WITH_LOCK_HELD, LispNames.SUPPORTS_THREADS_P))));
	}

	/**
	 * Returns the names of the {@code cl} functions, sorted alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> clFunctionNames() {
		return CL_FUNCTION_NAMES;
	}

	/**
	 * Returns the names of the {@code cl} macros, sorted alphabetically.
	 * @return the sorted macro names
	 */
	public static List<String> clMacroNames() {
		return CL_MACRO_NAMES;
	}

	/**
	 * Returns the names of the {@code cl} special forms, sorted alphabetically.
	 * @return the sorted special form names
	 */
	public static List<String> clSpecialFormNames() {
		return CL_SPECIAL_FORM_NAMES;
	}

	/**
	 * Returns the names of the operators that have no function value ({@code #'name} is
	 * an error): the special forms and the macros.
	 * @return the special operator names
	 */
	public static Set<String> specialOperatorNames() {
		return SPECIAL_OPERATOR_NAMES;
	}

	/**
	 * Returns the names of the functions exported by the {@code linalg} package, sorted
	 * alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> linalgFunctionNames() {
		return LINALG_FUNCTION_NAMES;
	}

	/**
	 * Returns the names of the functions exported by the {@code vec} package, sorted
	 * alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> vecFunctionNames() {
		return VEC_FUNCTION_NAMES;
	}

	/**
	 * Returns the names of the functions exported by the {@code usocket} package, sorted
	 * alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> usocketFunctionNames() {
		return USOCKET_FUNCTION_NAMES;
	}

	/**
	 * Returns every name exported by the {@code usocket} package (functions, macros and
	 * variables), for the bare-name usage detection in {@code UsocketLibrary}.
	 * @return the exported names
	 */
	public static Set<String> usocketExportedNames() {
		return USOCKET_EXTERNALS;
	}

	@SafeVarargs
	private static Set<String> union(Set<String>... sets) {
		Set<String> result = new HashSet<>();
		for (Set<String> set : sets) {
			result.addAll(set);
		}
		return Set.copyOf(result);
	}

	private static List<String> sorted(Set<String> names) {
		return names.stream().sorted().toList();
	}

	/**
	 * Registers (or replaces) a package.
	 * @param pkg the package to register
	 */
	public void define(LispPackage pkg) {
		this.packages.put(pkg.name(), pkg);
	}

	/**
	 * Registers a nickname for a package, so the nickname resolves everywhere the
	 * canonical name does.
	 * @param nickname the nickname
	 * @param packageName the canonical package name
	 */
	public void defineNickname(String nickname, String packageName) {
		this.nicknames.put(nickname, packageName);
	}

	/**
	 * Resolves a package designator to the canonical package name: a registered nickname
	 * maps to the package it names, any other name is returned unchanged.
	 * @param name the package name or nickname
	 * @return the canonical package name
	 */
	public String canonicalName(String name) {
		return this.nicknames.getOrDefault(name, name);
	}

	/**
	 * Returns the package with the given name (or nickname).
	 * @param name the package name
	 * @return the package
	 * @throws LispPackageException if no such package is registered
	 */
	public LispPackage get(String name) {
		LispPackage pkg = this.packages.get(canonicalName(name));
		if (pkg == null) {
			throw new LispPackageException("No such package: " + name);
		}
		return pkg;
	}

	/**
	 * Returns whether a package with the given name (or nickname) is registered.
	 * @param name the package name
	 * @return {@code true} if the package exists
	 */
	public boolean contains(String name) {
		return this.packages.containsKey(canonicalName(name));
	}

	/**
	 * Returns whether the given name is a symbol owned by the {@code cl} package (a
	 * standard function, macro, special form or variable, including car/cdr
	 * compositions).
	 * @param name the symbol name
	 * @return {@code true} if the name is a {@code cl} symbol
	 */
	public static boolean isClSymbol(String name) {
		return CL_SYMBOLS.contains(name) || LispMacroExpander.isCarCdrComposition(name);
	}

	/**
	 * Returns the fixed set of {@code cl}-package symbol names (functions, macros,
	 * special forms, variables, type specifiers). Excludes the car/cdr compositions,
	 * which are a pattern rather than a set (see
	 * {@link LispMacroExpander#isCarCdrComposition}).
	 * @return the {@code cl} symbol names
	 */
	public static Set<String> clSymbols() {
		return CL_SYMBOLS;
	}

	/**
	 * Returns the (lowercase) names of every built-in package plus every built-in
	 * nickname -- exactly the names {@link #isBuiltinPackageName} accepts. Used by the
	 * upcase reader's canonical fold and by the compiled backends' baked fold set.
	 * @return the built-in package and nickname names
	 */
	public static Set<String> builtinPackageAndNicknameNames() {
		Set<String> names = new HashSet<>(BUILTIN_PACKAGE_NAMES);
		names.addAll(BUILTIN_NICKNAMES.keySet());
		return names;
	}

	/**
	 * Composes a package-qualified symbol name for an external symbol, e.g.
	 * {@code qualify("rontolisp", "version")} yields {@code "rontolisp:version"}.
	 * @param pkg the package name
	 * @param member the member symbol name
	 * @return the qualified name
	 */
	public static String qualify(String pkg, String member) {
		return pkg + ":" + member;
	}

	/**
	 * Composes a package-qualified symbol name for an internal (non-exported) symbol,
	 * e.g. {@code qualifyInternal("rontolisp", "%json-parse")} yields
	 * {@code "rontolisp::%json-parse"}.
	 * @param pkg the package name
	 * @param member the member symbol name
	 * @return the qualified name
	 */
	public static String qualifyInternal(String pkg, String member) {
		return pkg + "::" + member;
	}

	/**
	 * Returns whether the given (lowercase) name is a built-in package name or built-in
	 * nickname. Static, like {@link #canonicalBuiltinName}: user {@code defpackage}
	 * packages are unknown here, which is exactly what the upcase reader mode needs (a
	 * user package spelled in source keeps its upcased spelling everywhere, so it stays
	 * self-consistent).
	 * @param name the candidate package name, already lowercased
	 * @return {@code true} if it names a built-in package or nickname
	 */
	public static boolean isBuiltinPackageName(String name) {
		return BUILTIN_PACKAGE_NAMES.contains(name) || BUILTIN_NICKNAMES.containsKey(name);
	}

	/**
	 * Resolves a built-in package nickname to the canonical package name ({@code rl} to
	 * {@code rontolisp}, {@code la} to {@code linalg}, ...); any other name is returned
	 * unchanged. Unlike the instance {@link #canonicalName(String)} it knows nothing of
	 * user {@code defpackage :nicknames}, so it is safe for the compile-path pre-passes
	 * that scan the program before package resolution runs.
	 * @param name the package name or built-in nickname
	 * @return the canonical package name
	 */
	public static String canonicalBuiltinName(String name) {
		return BUILTIN_NICKNAMES.getOrDefault(name, name);
	}

	/**
	 * Splits a package-qualified symbol name into its package and member parts. A single
	 * colon ({@code pkg:name}) references an external symbol, a double colon
	 * ({@code pkg::name}) an internal one, mirroring Common Lisp. Returns {@code null}
	 * for unqualified names and for keywords (a leading {@code :}). A built-in nickname
	 * in the package part is normalized to the canonical name ({@code rl:version} splits
	 * to {@code rontolisp}/{@code version}), so every pass that matches
	 * {@link QualifiedName#pkg()} against a built-in package sees one spelling — user
	 * {@code defpackage :nicknames} are instead resolved by {@code PackageResolver}
	 * through the instance {@link #canonicalName(String)}.
	 * @param name the symbol name
	 * @return the split parts, or {@code null} if the name is not package-qualified
	 */
	public static @Nullable QualifiedName splitQualified(String name) {
		if (name.startsWith("#:")) {
			// A gensym-style "uninterned" symbol (#:g1): not package-qualified.
			return null;
		}
		int idx = name.indexOf(':');
		if (idx <= 0) {
			// No colon, or a leading colon (keyword): not package-qualified.
			return null;
		}
		String pkg = canonicalBuiltinName(name.substring(0, idx));
		if (idx + 1 < name.length() && name.charAt(idx + 1) == ':') {
			return new QualifiedName(pkg, name.substring(idx + 2), true);
		}
		return new QualifiedName(pkg, name.substring(idx + 1), false);
	}

	/**
	 * A package-qualified symbol name split into its package and member parts.
	 *
	 * @param pkg the package part
	 * @param member the member symbol part
	 * @param internal whether the double-colon qualifier was used ({@code pkg::member}),
	 * granting access to internal (non-exported) symbols
	 */
	public record QualifiedName(String pkg, String member, boolean internal) {
	}

}

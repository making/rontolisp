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
			LispNames.DEFPACKAGE, LispNames.PROGV, LispNames.UNWIND_PROTECT, LispNames.TAGBODY, LispNames.GO,
			LispNames.CATCH, LispNames.THROW);

	/**
	 * The {@code cl} macros: operators expanded by
	 * {@link am.ik.rontolisp.macro.LispMacroExpander} that have no function value. Names
	 * that expand internally but are also usable as function values ({@code first},
	 * {@code length}, {@code 1+}, ...) are classified as functions.
	 */
	private static final Set<String> CL_MACROS = Set.of(LispNames.BLOCK, LispNames.COND, LispNames.CASE, LispNames.AND,
			LispNames.OR, LispNames.WHEN, LispNames.UNLESS, LispNames.DOTIMES, LispNames.SETF, LispNames.PUSH,
			LispNames.POP, LispNames.REMF, LispNames.LET_STAR, LispNames.DOLIST, LispNames.INCF, LispNames.DECF,
			LispNames.FORMAT, LispNames.WITH_OPEN_FILE, LispNames.WITH_OPEN_STREAM, LispNames.PROG1, LispNames.DO,
			LispNames.DO_STAR, LispNames.PROG2, LispNames.PSETQ, LispNames.PSETF, LispNames.TYPECASE, LispNames.ECASE,
			LispNames.ETYPECASE, LispNames.CTYPECASE, LispNames.CCASE, LispNames.ERROR, LispNames.CERROR,
			LispNames.TIME, LispNames.LOOP, LispNames.CHECK_TYPE, LispNames.ASSERT, LispNames.DECLARE,
			LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.THE, LispNames.EVAL_WHEN, LispNames.LOCALLY,
			LispNames.FLET, LispNames.LABELS, LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_LIST,
			LispNames.MULTIPLE_VALUE_CALL, LispNames.NTH_VALUE, LispNames.MULTIPLE_VALUE_SETQ,
			LispNames.MULTIPLE_VALUE_PROG1, LispNames.ROTATEF, LispNames.DESTRUCTURING_BIND,
			LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING, LispNames.WITH_STANDARD_IO_SYNTAX,
			LispNames.PUSHNEW, LispNames.DEFTYPE, LispNames.DEFINE_CONDITION, LispNames.DEFINE_MODIFY_MACRO,
			LispNames.DEFINE_SETF_EXPANDER, LispNames.DEFSETF, LispNames.DEFINE_COMPILER_MACRO, LispNames.RESTART_CASE,
			LispNames.MACROLET, LispNames.SYMBOL_MACROLET, LispNames.DEFINE_SYMBOL_MACRO, LispNames.MAKE_CONDITION,
			LispNames.DOCUMENTATION, LispNames.COMPLEMENT, LispNames.COMPLEX, LispNames.WARN, LispNames.SIGNAL,
			LispNames.RETURN_FROM, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.WITH_SLOTS,
			LispNames.WITH_ACCESSORS, LispNames.CHANGE_CLASS, LispNames.HANDLER_CASE, LispNames.IGNORE_ERRORS,
			LispNames.HANDLER_BIND, LispNames.WRITE_CHAR, LispNames.MAKE_SEQUENCE, LispNames.PROG, LispNames.PROG_STAR,
			LispNames.SHIFTF, LispNames.LOAD_TIME_VALUE, LispNames.TYPEP, LispNames.SLOT_BOUNDP,
			LispNames.SLOT_MAKUNBOUND, LispNames.SLOT_EXISTS_P, LispNames.PRINT_UNREADABLE_OBJECT,
			LispNames.WITH_PACKAGE_ITERATOR, LispNames.WITH_HASH_TABLE_ITERATOR, LispNames.DO_EXTERNAL_SYMBOLS,
			LispNames.DO_SYMBOLS, LispNames.WITH_COMPILATION_UNIT, LispNames.RESTART_BIND,
			LispNames.WITH_SIMPLE_RESTART, LispNames.PPRINT_LOGICAL_BLOCK);

	/**
	 * The {@code cl} functions: every standard name usable as a function value via
	 * {@code #'name}. Car/cdr compositions ({@code cadr}, {@code cddr}, ...) are
	 * recognized separately by {@link LispNames#isCarCdrComposition} and are not
	 * enumerated here.
	 */
	private static final Set<String> CL_FUNCTIONS = Set.of(LispNames.FUNCALL, LispNames.ADD, LispNames.SUB,
			LispNames.MUL, LispNames.DIV, LispNames.MOD, LispNames.REM, LispNames.ABS, LispNames.MIN, LispNames.MAX,
			LispNames.SQRT, LispNames.ISQRT, LispNames.EXPT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS,
			LispNames.TAN, LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
			LispNames.TANH, LispNames.RANDOM, LispNames.MAKE_RANDOM_STATE, LispNames.GCD, LispNames.LCM,
			LispNames.SIGNUM, LispNames.EQ, LispNames.EQ_GENERAL, LispNames.EQL, LispNames.EQUAL, LispNames.EQUALP,
			LispNames.LT, LispNames.GT, LispNames.LE, LispNames.GE, LispNames.CONS, LispNames.CAR, LispNames.CDR,
			LispNames.LIST, LispNames.APPEND, LispNames.NTHCDR, LispNames.RPLACA, LispNames.RPLACD, LispNames.MAPCAR,
			LispNames.MAP, LispNames.MAP_INTO, LispNames.MAPC, LispNames.MAPCAN, LispNames.APPLY, LispNames.SORT,
			LispNames.REDUCE, LispNames.EVERY, LispNames.SOME, LispNames.REMOVE, LispNames.REMOVE_IF,
			LispNames.REMOVE_IF_NOT, LispNames.NOT, LispNames.NULL, LispNames.ATOM, LispNames.NUMBERP,
			LispNames.INTEGERP, LispNames.FLOATP, LispNames.RATIONALP, LispNames.NUMERATOR, LispNames.DENOMINATOR,
			LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP, LispNames.FLOAT,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND, LispNames.FTRUNCATE,
			LispNames.FFLOOR, LispNames.FCEILING, LispNames.FROUND, LispNames.ONE_PLUS, LispNames.ONE_MINUS,
			LispNames.ZEROP, LispNames.PLUSP, LispNames.MINUSP, LispNames.EVENP, LispNames.ODDP, LispNames.FIRST,
			LispNames.SECOND, LispNames.THIRD, LispNames.FOURTH, LispNames.FIFTH, LispNames.SIXTH, LispNames.SEVENTH,
			LispNames.EIGHTH, LispNames.NINTH, LispNames.TENTH, LispNames.NTH, LispNames.PRINT, LispNames.PRIN1,
			LispNames.PRINC, LispNames.TERPRI, LispNames.FRESH_LINE, LispNames.READ_LINE, LispNames.READ,
			LispNames.EVAL, LispNames.LOAD, LispNames.REQUIRE, LispNames.PROVIDE, LispNames.SYMBOL_FUNCTION,
			LispNames.LENGTH, LispNames.REVERSE, LispNames.MEMBER, LispNames.FIND, LispNames.FIND_IF,
			LispNames.FIND_IF_NOT, LispNames.MEMBER_IF, LispNames.POSITION, LispNames.POSITION_IF,
			LispNames.POSITION_IF_NOT, LispNames.COUNT, LispNames.COUNT_IF, LispNames.ASSOC, LispNames.ASSOC_IF,
			LispNames.LAST, LispNames.BUTLAST, LispNames.GETF, LispNames.REMOVE_DUPLICATES, LispNames.DELETE_DUPLICATES,
			LispNames.NCONC, LispNames.REST, LispNames.PRINC_TO_STRING,
			// The printer generic: a defmethod on it belongs to cl, so a method defined
			// inside a package that uses cl specializes CL:PRINT-OBJECT rather than
			// minting that package's own (quri's uri method).
			LispNames.PRINT_OBJECT,
			// The instance-initialization generics, cl-owned for the same reason: CL has
			// ONE of each, and make-instance's protocol chain must reach a hook whatever
			// package defined it (cl-ppcre's initialize-instance :after and postmodern's
			// shared-initialize :after must join the same generics).
			LispNames.INITIALIZE_INSTANCE, LispNames.REINITIALIZE_INSTANCE, LispNames.SHARED_INITIALIZE,
			LispNames.PRIN1_TO_STRING, LispNames.CONCATENATE, LispNames.STRING, LispNames.STRING_UPCASE,
			LispNames.STRING_DOWNCASE, LispNames.STRING_CAPITALIZE, LispNames.SUBSEQ, LispNames.STRING_EQ,
			LispNames.STRING_LT, LispNames.STRING_GT, LispNames.STRING_LE, LispNames.STRING_GE, LispNames.STRING_NE,
			LispNames.STRING_EQUAL, LispNames.STRING_LESSP, LispNames.STRING_GREATERP, LispNames.STRING_NOT_GREATERP,
			LispNames.STRING_NOT_LESSP, LispNames.STRING_NOT_EQUAL, LispNames.STRING_TRIM, LispNames.STRING_LEFT_TRIM,
			LispNames.STRING_RIGHT_TRIM, LispNames.OPEN, LispNames.CLOSE, LispNames.PROBE_FILE, LispNames.DIRECTORY,
			LispNames.PATHNAME_DIRECTORY, LispNames.CONSTANTLY, LispNames.WRITE_LINE, LispNames.READ_BYTE,
			LispNames.WRITE_BYTE, LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE, LispNames.IDENTITY,
			LispNames.COPY_LIST, LispNames.COPY_TREE, LispNames.TREE_EQUAL, LispNames.NREVERSE, LispNames.MAKE_LIST,
			LispNames.UNION, LispNames.SET_EXCLUSIVE_OR, LispNames.COUNT_IF_NOT, LispNames.MERGE,
			LispNames.INTERSECTION, LispNames.SET_DIFFERENCE, LispNames.ADJOIN, LispNames.SUBSETP, LispNames.LOGAND,
			LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.LOGANDC1, LispNames.LOGANDC2,
			LispNames.LOGORC1, LispNames.LOGORC2, LispNames.ASH, LispNames.INTEGER_LENGTH, LispNames.LOGBITP,
			LispNames.LOGTEST, LispNames.BYTE, LispNames.BYTE_SIZE, LispNames.BYTE_POSITION, LispNames.LDB,
			LispNames.DPB, LispNames.LIST_STAR, LispNames.ACONS, LispNames.ENDP, LispNames.ELT, LispNames.RASSOC,
			LispNames.RASSOC_IF, LispNames.PAIRLIS, LispNames.COPY_ALIST, LispNames.REVAPPEND, LispNames.NRECONC,
			LispNames.MAPLIST, LispNames.MAPCON, LispNames.MAPL, LispNames.NOTANY, LispNames.NOTEVERY, LispNames.DELETE,
			LispNames.DELETE_IF, LispNames.DELETE_IF_NOT, LispNames.SUBSTITUTE, LispNames.SUBST, LispNames.NSUBSTITUTE,
			LispNames.SUBSTITUTE_IF, LispNames.SUBSTITUTE_IF_NOT, LispNames.NSUBSTITUTE_IF,
			LispNames.NSUBSTITUTE_IF_NOT, LispNames.SEARCH, LispNames.MISMATCH, LispNames.GET_UNIVERSAL_TIME,
			LispNames.ENCODE_UNIVERSAL_TIME, LispNames.DECODE_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME,
			LispNames.GET_INTERNAL_RUN_TIME, LispNames.SLEEP, LispNames.FORCE_OUTPUT, LispNames.FINISH_OUTPUT,
			LispNames.CLEAR_OUTPUT, LispNames.LISTEN, LispNames.READ_FROM_STRING, LispNames.PARSE_INTEGER,
			LispNames.CHAR, LispNames.SCHAR, LispNames.CHAR_CODE, LispNames.CODE_CHAR, LispNames.CHAR_EQ,
			LispNames.CHAR_LT, LispNames.CHAR_LE, LispNames.CHAR_GT, LispNames.CHAR_GE, LispNames.CHAR_NE,
			LispNames.CHAR_EQUAL, LispNames.CHAR_NOT_EQUAL, LispNames.CHAR_LESSP, LispNames.CHAR_GREATERP,
			LispNames.CHAR_NOT_LESSP, LispNames.CHAR_NOT_GREATERP, LispNames.GRAPHIC_CHAR_P, LispNames.STANDARD_CHAR_P,
			LispNames.CHAR_UPCASE, LispNames.CHAR_DOWNCASE, LispNames.CHARACTERP, LispNames.ALPHA_CHAR_P,
			LispNames.ALPHANUMERICP, LispNames.LDIFF, LispNames.SUBLIS, LispNames.GENTEMP, LispNames.MAKE_LOAD_FORM,
			LispNames.MAKE_LOAD_FORM_SAVING_SLOTS, LispNames.SXHASH, LispNames.SBIT, LispNames.BIT,
			LispNames.BOTH_CASE_P, LispNames.SPECIAL_OPERATOR_P, LispNames.MACRO_FUNCTION,
			LispNames.COMPILED_FUNCTION_P, LispNames.FUNCTION_LAMBDA_EXPRESSION, LispNames.LIST_ALL_PACKAGES,
			LispNames.USE_PACKAGE, LispNames.EXPORT, LispNames.UNEXPORT, LispNames.FIND_CLASS, LispNames.GET,
			LispNames.SYMBOL_PLIST, LispNames.DIGIT_CHAR_P, LispNames.DIGIT_CHAR, LispNames.MAKE_HASH_TABLE,
			LispNames.GETHASH, LispNames.REMHASH, LispNames.CLRHASH, LispNames.HASH_TABLE_COUNT,
			LispNames.HASH_TABLE_TEST, LispNames.HASH_TABLE_SIZE, LispNames.HASH_TABLE_REHASH_SIZE,
			LispNames.HASH_TABLE_REHASH_THRESHOLD, LispNames.HASH_TABLE_P, LispNames.MAPHASH, LispNames.MAKE_ARRAY,
			LispNames.AREF, LispNames.VECTOR, LispNames.SVREF, LispNames.ARRAY_DIMENSIONS, LispNames.ARRAY_DIMENSION,
			LispNames.ARRAY_RANK, LispNames.ARRAY_TOTAL_SIZE, LispNames.ROW_MAJOR_AREF, LispNames.ARRAY_ROW_MAJOR_INDEX,
			LispNames.COERCE, LispNames.GENSYM, LispNames.MACROEXPAND, LispNames.MACROEXPAND_1, LispNames.VALUES,
			LispNames.WRITE_STRING, LispNames.WRITE_TO_STRING, LispNames.WRITE, LispNames.PPRINT,
			LispNames.PPRINT_NEWLINE, LispNames.PPRINT_INDENT, LispNames.PPRINT_TAB, LispNames.COPY_PPRINT_DISPATCH,
			LispNames.SET_PPRINT_DISPATCH, LispNames.PPRINT_DISPATCH, LispNames.SYMBOL_NAME, LispNames.INTERN,
			LispNames.FIND_SYMBOL, LispNames.MAKE_SYMBOL, LispNames.BOUNDP, LispNames.FBOUNDP, LispNames.FMAKUNBOUND,
			LispNames.SYMBOL_VALUE, LispNames.FUNCTIONP, LispNames.VALUES_LIST, LispNames.NE, LispNames.FILL_POINTER,
			LispNames.ARRAY_HAS_FILL_POINTER_P, LispNames.ADJUSTABLE_ARRAY_P, LispNames.VECTOR_PUSH,
			LispNames.VECTOR_POP, LispNames.VECTOR_PUSH_EXTEND, LispNames.ARRAY_ELEMENT_TYPE, LispNames.ADJUST_ARRAY,
			LispNames.ARRAY_DISPLACEMENT, LispNames.STABLE_SORT, LispNames.COPY_SEQ, LispNames.READ_CHAR,
			LispNames.PEEK_CHAR, LispNames.READ_CHAR_NO_HANG, LispNames.UNREAD_CHAR,
			LispNames.MAKE_STRING_OUTPUT_STREAM, LispNames.MAKE_STRING_INPUT_STREAM, LispNames.GET_OUTPUT_STREAM_STRING,
			LispNames.MAKE_SYNONYM_STREAM, LispNames.SYNONYM_STREAM_SYMBOL, LispNames.VECTORP, LispNames.ARRAYP,
			LispNames.MAKE_STRING, LispNames.REPLACE, LispNames.FILL, LispNames.LOWER_CASE_P, LispNames.UPPER_CASE_P,
			LispNames.CONSTANTP, LispNames.GET_SETF_EXPANSION, LispNames.STREAMP, LispNames.SIMPLE_STRING_P,
			LispNames.MASK_FIELD, LispNames.SCALE_FLOAT, LispNames.DECODE_FLOAT, LispNames.SUBTYPEP,
			LispNames.CHAR_NAME, LispNames.FDEFINITION, LispNames.FILE_POSITION, LispNames.FILE_LENGTH,
			LispNames.FILE_WRITE_DATE, LispNames.ENSURE_DIRECTORIES_EXIST, LispNames.MAKE_BROADCAST_STREAM,
			LispNames.PATHNAMEP, LispNames.INPUT_STREAM_P, LispNames.OUTPUT_STREAM_P, LispNames.OPEN_STREAM_P,
			LispNames.STREAM_ELEMENT_TYPE, LispNames.CLASS_OF, LispNames.CLASS_NAME, LispNames.ALLOCATE_INSTANCE,
			LispNames.COMPILE, LispNames.SIMPLE_CONDITION_FORMAT_CONTROL, LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS,
			LispNames.TYPE_ERROR_DATUM, LispNames.TYPE_ERROR_EXPECTED_TYPE, LispNames.CELL_ERROR_NAME,
			LispNames.UNBOUND_SLOT_INSTANCE, LispNames.MAKE_PATHNAME, LispNames.MERGE_PATHNAMES, LispNames.TRUENAME,
			LispNames.PATHNAME, LispNames.PARSE_NAMESTRING, LispNames.PATHNAME_NAME, LispNames.PATHNAME_TYPE,
			LispNames.DELETE_FILE, LispNames.Y_OR_N_P, LispNames.NAMESTRING_CL, LispNames.COPY_READTABLE,
			LispNames.SET_DISPATCH_MACRO_CHARACTER, LispNames.READTABLE_CASE, LispNames.FIND_PACKAGE,
			LispNames.SYMBOL_PACKAGE, LispNames.PACKAGE_NAME, LispNames.TYPE_OF, LispNames.INVOKE_RESTART,
			LispNames.FIND_RESTART, LispNames.COMPUTE_RESTARTS, LispNames.RESTART_NAME, LispNames.MUFFLE_WARNING,
			LispNames.ABORT, LispNames.CONTINUE, LispNames.USE_VALUE, LispNames.STORE_VALUE, LispNames.REMPROP,
			LispNames.IMPORT, LispNames.PACKAGE_USE_LIST, LispNames.PACKAGE_USED_BY_LIST,
			LispNames.PACKAGE_SHADOWING_SYMBOLS, LispNames.PATHNAME_HOST, LispNames.PATHNAME_DEVICE,
			LispNames.PATHNAME_VERSION, LispNames.WILD_PATHNAME_P, LispNames.ENOUGH_NAMESTRING,
			LispNames.TRANSLATE_PATHNAME, LispNames.TRANSLATE_LOGICAL_PATHNAME, LispNames.LOGICAL_PATHNAME,
			LispNames.RENAME_FILE, LispNames.FILE_NAMESTRING, LispNames.DIRECTORY_NAMESTRING, LispNames.HOST_NAMESTRING,
			LispNames.NSTRING_UPCASE, LispNames.NSTRING_DOWNCASE, LispNames.NSTRING_CAPITALIZE,
			LispNames.LISP_IMPLEMENTATION_TYPE, LispNames.LISP_IMPLEMENTATION_VERSION, LispNames.SOFTWARE_TYPE,
			LispNames.SOFTWARE_VERSION, LispNames.MACHINE_TYPE, LispNames.MACHINE_VERSION, LispNames.MACHINE_INSTANCE,
			LispNames.SHORT_SITE_NAME, LispNames.LONG_SITE_NAME, LispNames.USER_HOMEDIR_PATHNAME, LispNames.COPY_SYMBOL,
			LispNames.INVOKE_DEBUGGER, LispNames.REMOVE_METHOD, LispNames.COMPILE_FILE,
			LispNames.COMPILE_FILE_PATHNAME);

	/** The {@code cl} variables. */
	private static final Set<String> CL_VARIABLES = Set.of(LispNames.PACKAGE_VAR, LispNames.READ_DEFAULT_FLOAT_FORMAT,
			LispNames.ARRAY_DIMENSION_LIMIT, LispNames.ARRAY_TOTAL_SIZE_LIMIT, LispNames.CHAR_CODE_LIMIT,
			LispNames.MOST_POSITIVE_FIXNUM, LispNames.MOST_NEGATIVE_FIXNUM, LispNames.INTERNAL_TIME_UNITS_PER_SECOND,
			LispNames.PRINT_CIRCLE_VAR, LispNames.PRINT_ESCAPE_VAR, LispNames.PRINT_READABLY_VAR,
			LispNames.FEATURES_VAR, LispNames.STANDARD_OUTPUT_VAR, LispNames.ERROR_OUTPUT_VAR,
			LispNames.STANDARD_INPUT_VAR, LispNames.READTABLE_VAR, LispNames.LAMBDA_LIST_KEYWORDS,
			LispNames.LOAD_PATHNAME_VAR, LispNames.LOAD_TRUENAME_VAR, LispNames.COMPILE_FILE_PATHNAME_VAR,
			LispNames.COMPILE_FILE_TRUENAME_VAR, LispNames.LOAD_VERBOSE_VAR, LispNames.LOAD_PRINT_VAR,
			LispNames.COMPILE_VERBOSE_VAR, LispNames.COMPILE_PRINT_VAR, LispNames.READ_EVAL_VAR,
			LispNames.PRINT_PRETTY_VAR, LispNames.PRINT_RIGHT_MARGIN_VAR, LispNames.PRINT_MISER_WIDTH_VAR,
			LispNames.PRINT_LINES_VAR, LispNames.PRINT_PPRINT_DISPATCH_VAR, LispNames.PRINT_LENGTH_VAR,
			LispNames.PRINT_LEVEL_VAR, LispNames.PRINT_BASE_VAR, LispNames.PRINT_RADIX_VAR, LispNames.PRINT_CASE_VAR,
			LispNames.PRINT_ARRAY_VAR, LispNames.PRINT_GENSYM_VAR, LispNames.MODULES_VAR, LispNames.TRACE_OUTPUT_VAR,
			LispNames.DEBUG_IO_VAR, LispNames.QUERY_IO_VAR, LispNames.TERMINAL_IO_VAR,
			LispNames.DEFAULT_PATHNAME_DEFAULTS_VAR);

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
			"CHARACTER", "BASE-CHAR", "STANDARD-CHAR", "SATISFIES", "OTHERWISE", "STREAM",
			// The stream SUBtypes and the readtable type. Every stream is a
			// self-describing value carrying its KIND, so `file-stream`,
			// `string-stream` and `synonym-stream` all have exact tests; a "readtable"
			// is the nil token the non-readtable-driven reader hands out. See
			// LispMacroExpander.makeTypeTest.
			"FILE-STREAM", "STRING-STREAM", "SYNONYM-STREAM", "READTABLE",
			// More empty types (nothing satisfies them, by the same must-not-become-
			// pkg::name rule): no bit-vector value exists (the bit type is dead), a
			// defgeneric's dispatcher is a plain function, a defstruct's
			// class metaobject is a standard-class and built-in types have no
			// metaobjects -- trivia level2 dispatches typecase/etypecase over all six.
			"BIT-VECTOR", "SIMPLE-BIT-VECTOR", "GENERIC-FUNCTION", "STANDARD-GENERIC-FUNCTION", "STRUCTURE-CLASS",
			"BUILT-IN-CLASS",
			// CL symbols used as NAMES by libraries: trivia's class/structure/type
			// patterns are (defpattern class ...) &c on the CL symbols, so the
			// defpattern site (inside trivia's package) and a user's pattern site must
			// resolve to the SAME bare spelling or the pattern-namespace lookup misses.
			// CLASS is additionally a REAL class once the MOP surface seeds (the
			// superclass of standard-class, .kb/clos.md), so the bare spelling is also
			// what makes (typep x 'class) find it.
			"CLASS", "STRUCTURE", "TYPE",
			// The package TYPE name (a package value is find-package's keyword answer,
			// .kb/symbol-runtime-api.md): cl-package-locks' (etypecase p (package p)
			// (symbol ...)) must not resolve it to cl-package-locks::package.
			"PACKAGE",
			// The root class name: (find-class 'standard-object) must reach the
			// find-class fallback (ClosRegistry.FIND_CLASS_ONLY_CLASS_NAMES) as the bare
			// CL spelling -- mito's map-all-superclasses eq-compares superclass
			// metaobjects against it from inside mito's own package. STANDARD-CLASS is
			// its metaclass sibling: the compiled %find-class matches SPELLINGS, so a
			// package-local MITO...::STANDARD-CLASS resolution missed the seeded entry
			// (the interpreter's registry normalizes spellings and hid the gap).
			"STANDARD-OBJECT", "STANDARD-CLASS");

	/**
	 * The built-in CONDITION class names, taken straight from the hierarchy
	 * {@link ClosRegistry} seeds so the two can never drift apart. They belong to
	 * {@code cl} for the same reason the type names above do, and the consequence is
	 * sharper: a condition name is what a {@code handler-case} clause, a
	 * {@code define-condition} parent and a RUNTIME {@code (typep c ty)} specifier all
	 * spell, so a package-local {@code MY-PKG::TYPE-ERROR} made the same condition two
	 * different symbols in two packages and left the runtime type test -- which matches
	 * the registry's plain class name by spelling -- answering nil.
	 */
	private static final Set<String> CL_CONDITION_TYPES = Set.copyOf(ClosRegistry.CONDITION_CLASS_NAMES);

	/**
	 * Internal {@code %}-prefixed helpers owned by {@code cl} but excluded from the
	 * introspection listings.
	 */
	private static final Set<String> CL_INTERNALS = Set.of(LispNames.REMF_TAIL, LispNames.STRING_CONCAT,
			LispNames.FIXED_DECIMAL, LispNames.SEQ_STRING, LispNames.SEQ_INT_VECTOR, LispNames.BLOCK_INTERNAL,
			LispNames.FN_BLOCK_INTERNAL, LispNames.ERROR_INTERNAL, LispNames.PUTHASH, LispNames.ASET,
			LispNames.READ_SEQUENCE_PACKED, LispNames.WRITE_SEQUENCE_PACKED, LispNames.ROW_MAJOR_ASET,
			LispNames.MAKE_STRING_OUTPUT_STREAM_INTERNAL, LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL,
			LispNames.STRING_STREAM_CONTENTS_INTERNAL, LispNames.PEEK_CHAR_INTERNAL, LispNames.ARRAYP_INTERNAL,
			LispNames.SIMPLE_ARRAY_P_INTERNAL, LispNames.STRING_DIMENSION_INTERNAL, LispNames.MV_SPILL,
			LispNames.SET_FILL_POINTER, LispNames.ARRAY_BECOME, LispNames.ARRAY_ALIKE, LispNames.ARRAY_DISP_TARGET,
			LispNames.ARRAY_DEFAULT_ELEMENT, LispNames.ARRAY_ADOPT_ELEMENT_TYPE, LispNames.ARRAY_DISP_OFFSET,
			LispNames.ARRAY_UNDISPLACE, LispNames.FIND_SYMBOL_STATUS, LispNames.WARN_INTERNAL, LispNames.SCHAR_SET,
			LispNames.IEEE754_DOUBLE_BITS, LispNames.IEEE754_DOUBLE_FROM_BITS, LispNames.IEEE754_SINGLE_BITS,
			LispNames.IEEE754_SINGLE_FROM_BITS, LispNames.READ_EVAL, LispNames.READ_EVAL_TEMPLATE,
			LispNames.READ_EVAL_UNREADABLE, LispNames.SUBSEQ_CORE, LispNames.NLX_TAG_INTERNAL,
			LispNames.NLX_CATCH_INTERNAL, LispNames.NLX_THROW_INTERNAL, LispNames.STRING_COMPARE,
			LispNames.CHAR_FOLD_CHAIN, LispNames.SET_XOR_MATCH, LispNames.RD_DATUM, LispNames.RD_DISPATCH,
			LispNames.RD_SKIP, LispNames.RD_SKIP_LINE, LispNames.RD_BLOCK_COMMENT, LispNames.RD_LIST,
			LispNames.RD_STRING, LispNames.RD_BARS, LispNames.RD_TOKEN_REST, LispNames.RD_CHAR_LITERAL,
			LispNames.RD_SHARP, LispNames.RD_WHITESPACE_P, LispNames.RD_TERMINATING_P,
			LispNames.PPRINT_DISPATCH_DEFAULT, LispNames.OBJ_NEW, LispNames.OBJ_REF, LispNames.OBJ_SET,
			LispNames.OBJ_IS, LispNames.OBJ_TAG, LispNames.OBJ_P, LispNames.OBJ_SLOTS, LispNames.RUN_HANDLERS_INTERNAL,
			LispNames.HB_GUARD_INTERNAL, LispNames.HC_MATCH_INTERNAL, LispNames.HANDLERS_RAN_VAR,
			LispNames.HANDLER_CLUSTERS_VAR, LispNames.RESTART_CLUSTERS_VAR, LispNames.RESTART_RECORD_TAG,
			LispNames.LIST_DIRECTORY, LispNames.DIR_NAMESTRING, LispNames.WILD_MATCH, LispNames.PATHNAME_TYPED_P,
			LispNames.SLEEP_MS, LispNames.MAKE_DIRECTORIES, LispNames.PATHNAME_SPLIT, LispNames.MAKE_ARRAY_ET_INTERNAL,
			LispNames.MAKE_ARRAY_ET_FP_INTERNAL, LispNames.MAKE_BROADCAST_STREAM_INTERNAL,
			LispNames.BROADCAST_STREAM_CLASS, LispNames.BROADCAST_STREAM_COMPONENTS,
			LispNames.PATHNAME_DIRECTORY_STRING, LispNames.PATHNAME_COMPONENT_STRING, LispNames.DELETE_FILE_INTERNAL,
			LispNames.SET_SYMBOL_FUNCTION_INTERNAL, LispNames.FENV_FUNCTION_INTERNAL, LispNames.TEMP_FILE_NAME,
			LispNames.PROBE_FILE_INTERNAL, LispNames.PATH_NS, LispNames.STREAM_TARGET, LispNames.CLOSE_INTERNAL,
			LispNames.MACRO_FN_INTERNAL, LispNames.MACRO_EXPANDER_STUB, LispNames.WILD_COMPONENT_P,
			LispNames.WILD_CAPTURES, LispNames.WILD_INFERIORS_AT, LispNames.PATHNAME_DIRECTORY_COMPONENT,
			LispNames.DIRECTORY_IN, LispNames.DIRECTORY_SUBDIRS, LispNames.WILD_DIRS, LispNames.PATH_DIR_PARTS,
			LispNames.RENAME_FILE_INTERNAL, LispNames.PRINT_CASED_INTERNAL, LispNames.PRINT_CASE_FOLD_INTERNAL,
			LispNames.UNESCAPED_SYMBOL_TEXT_INTERNAL, LispNames.PRINT_CASED_WALK_INTERNAL,
			LispNames.PRINT_CASED_ON_PATH_INTERNAL, LispNames.PRINT_CASED_CHAIN_STOP_INTERNAL,
			LispNames.PRINT_RADIXED_INTERNAL, LispNames.PRINT_IN_BASE_INTERNAL,
			LispNames.PRINT_CASED_UNQUALIFIED_INTERNAL, LispNames.SYMBOL_PRINT_BARE_P_INTERNAL,
			LispNames.PRINT_PACKAGE_RAW_P_INTERNAL, LispNames.PRINT_CASED_FOLD_LEAF_INTERNAL,
			LispNames.PRINT_CASED_RADIXED_LEAF_INTERNAL, LispNames.HOST_GETENV, LispNames.HOST_GETCWD,
			LispNames.HOST_EXIT, LispNames.HOST_ARGV, LispNames.GETENV_OVERRIDE, LispNames.GETENV_OVERRIDE_SET,
			LispNames.NSTRING_REPLACE, LispNames.TARGET_MACHINE_TYPE);

	/**
	 * The names of the symbols owned by the {@code cl} package, derived as the union of
	 * the categorized sets above.
	 */
	private static final Set<String> CL_SYMBOLS = union(CL_SPECIAL_FORMS, CL_MACROS, CL_FUNCTIONS, CL_VARIABLES,
			CL_INTERNALS, CL_TYPES, CL_CONDITION_TYPES);

	/**
	 * The exported {@code cl} symbols: everything but the {@code %}-prefixed internals
	 * (car/cdr compositions are recognized separately by
	 * {@link LispNames#isCarCdrComposition} and are also external).
	 */
	private static final Set<String> CL_EXTERNALS = union(CL_SPECIAL_FORMS, CL_MACROS, CL_FUNCTIONS, CL_VARIABLES,
			CL_TYPES, CL_CONDITION_TYPES);

	/**
	 * The functions exported by the {@code linalg} package (numpy-style vector/matrix
	 * operations), implemented in {@code linalg.lisp} (see {@code LinalgLibrary}). The
	 * names are plain strings rather than {@link LispNames} constants because they exist
	 * only as Lisp-source defuns -- no evaluator or compiler dispatches on them.
	 */
	private static final Set<String> LINALG_FUNCTIONS = Set.of("ZEROS", "ONES", "FULL", "EYE", "ARANGE", "LINSPACE",
			"FROM-LIST", "TO-LIST", "SHAPE", "NDIM", "SIZE", "RESHAPE", "FLATTEN", "TRANSPOSE", "PAD", "ADD", "SUB",
			"MUL", "DIV", "EMAP", "DOT", "MATMUL", "OUTER", "CROSS", "SUM", "MEAN", "AMAX", "AMIN", "ARGMAX", "ARGMIN",
			"NORM", "TRACE", "DET", "INV", "SOLVE", "ARRAY-EQUAL", "EXP", "LOG", "TANH", "SIN", "COS", "TAN", "ASIN",
			"ACOS", "ATAN", "SINH", "COSH", "SQRT", "ABS", "SQUARE", "NEGATIVE", "SIGN", "RECIPROCAL", "MAXIMUM",
			"MINIMUM", "CLIP", "RELU", "ERF", "DIFF", "GRADIENT", "ZEROS-LIKE", "SEED", "RAND", "RANDN", "UNIFORM",
			"CHOICE", "PERMUTATION", "TAKE-ROWS", "ROW", "GATHER", "ONE-HOT", "EQUAL", "GREATER", "GREATER-EQUAL",
			"LESS", "LESS-EQUAL", "+", "-", "*", "/", "CONCATENATE", "STACK", "EXPAND-DIMS", "SQUEEZE", "SLICE", "TRIU",
			"TRIL", "VAR", "STD", "WHERE", "POWER", "SOFTMAX", "LOG-SOFTMAX");

	private static final List<String> LINALG_FUNCTION_NAMES = sorted(LINALG_FUNCTIONS);

	/**
	 * The names exported by the {@code torch} package (a PyTorch-style tensor with
	 * reverse-mode autograd over the {@code linalg} kernels), implemented in
	 * {@code torch.lisp} (see {@code TorchLibrary}). Plain strings like the linalg names
	 * -- they exist only as Lisp-source defuns -- except {@code no-grad}, the one macro,
	 * which is a built-in {@code LispMacroExpander} expansion and is dispatched on by
	 * name ({@link LispNames#TORCH_NO_GRAD}).
	 */
	private static final Set<String> TORCH_FUNCTIONS = Set.of("TENSOR", "TENSORP", "DATA", "GRAD", "SHAPE", "ITEM",
			"DETACH", "ZERO-GRAD", "REQUIRES-GRAD-P", "BACKWARD", LispNames.TORCH_NO_GRAD, "RESHAPE", "VIEW",
			"TRANSPOSE", "UNSQUEEZE", "SQUEEZE", "CAT", "STACK", "SLICE", "ADD", "SUB", "MUL", "DIV", "NEG", "POWER",
			"EXP", "LOG", "SQRT", "TANH", "MATMUL", "SUM", "MEAN", "VAR", "STD", "AMAX", "ARGMAX", "SOFTMAX",
			"LOG-SOFTMAX", "RELU", "ERF", "GELU", "TOPK", "MULTINOMIAL", "MASKED-FILL", "GATHER", "INDEX-SELECT",
			"MODULE", "MODULEP", "MODULE-KIND", "FIELD", "FIELDS", "SET-DATA", "SET-FIELD", "FORWARD", "PARAMETER",
			"PARAMETERS", "TRAIN", "EVAL", "TRAINING-P", "LINEAR", "EMBEDDING", "SEQUENTIAL", "LAYER-NORM", "DROPOUT",
			"MSE-LOSS", "CROSS-ENTROPY-LOSS", "OPTIMIZER", "OPTIMIZERP", "OPTIMIZER-KIND", "OPTIMIZER-PARAMS", "STEP",
			"STEP-COUNT", "SGD", "ADAM", "ADAMW", "CLIP-GRAD-NORM", "PAD-SEQUENCE", "SHUFFLED-BATCHES", "PADDING-MASK",
			"SUBSEQUENT-MASK");

	private static final List<String> TORCH_FUNCTION_NAMES = sorted(TORCH_FUNCTIONS);

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
			LispNames.VEC_RELU_INTO, LispNames.VEC_CLIP_INTO, LispNames.VEC_DIV, LispNames.VEC_DIV_INTO,
			LispNames.VEC_PLUS, LispNames.VEC_MINUS, LispNames.VEC_STAR, LispNames.VEC_SLASH);

	private static final List<String> VEC_FUNCTION_NAMES = sorted(VEC_FUNCTIONS);

	/**
	 * The functions exported by the {@code appkit} package (a Cocoa widget layer over the
	 * {@code objc} verbs), implemented in {@code appkit.lisp} (see
	 * {@code AppKitLibrary}). Plain strings, like {@code linalg}: they exist only as
	 * Lisp-source defuns.
	 */
	private static final Set<String> APPKIT_FUNCTIONS = Set.of("WINDOW", "LABEL", "BUTTON", "PANEL", "COLOR", "FONT",
			"SET-TEXT", "SET-COLOR", "TEXT", "ON-CLICK", "CLICK", "TIMER", "MENU", "STATUS-ITEM", "QUIT", "CLOSE",
			"VISIBLE-P", "WAIT");

	private static final List<String> APPKIT_FUNCTION_NAMES = sorted(APPKIT_FUNCTIONS);

	/**
	 * The names exported by the {@code geom} package (solid modeling: rigid transforms, a
	 * scene graph and boundary-represented solids), implemented in {@code geom.lisp} (see
	 * {@code GeomLibrary}). Plain strings, like {@code linalg}: they exist only as
	 * Lisp-source definitions. The three type names ({@code transform}, {@code node},
	 * {@code solid}) and {@code bounds} are CLOS classes rather than functions, and are
	 * registered here for the same reason -- a {@code (typep x 'geom:solid)} spelling has
	 * to resolve.
	 */
	private static final Set<String> GEOM_FUNCTIONS = Set.of("TRANSFORM", "MAKE-TRANSFORM", "TRANSLATION-OF",
			"ROTATION-OF", "COMPOSE", "INVERT", "TRANSFORM-POINT", "INVERSE-TRANSFORM-POINT", "AXIS-ANGLE-MATRIX",
			"RPY-MATRIX", "AXIS-VECTOR", "VEC3", "NODE", "MAKE-NODE", "LOCAL-TRANSFORM", "WORLD-TRANSFORM",
			"WORLD-TRANSLATION", "WORLD-ROTATION", "PARENT-OF", "CHILDREN-OF", "ATTACH", "DETACH", "TRANSLATE",
			"ROTATE", "PLACE", "REORIENT", "SOLID", "BOX", "CYLINDER", "CONE", "SPHERE", "TORUS", "EXTRUSION",
			"REVOLUTION", "POLYHEDRON", "READ-MODEL", "READ-OBJ", "READ-STL", "READ-PLY", "READ-GLTF", "ARROW", "TRIAD",
			"FACETS-OF", "VERTICES-OF", "COLOR-OF", "LABEL-OF", "SCALE", "NSCALE", "MESH", "WIREFRAME",
			"MESH-TRIANGLE-COUNT", "USER-DATA", "BOUNDS", "LOWER-OF", "UPPER-OF", "BOUNDS-CENTER", "BOUNDS-EXTENT",
			"BOUNDS-UNION", "VOLUME", "CENTROID", "SURFACE-AREA", "UNION", "DIFFERENCE", "INTERSECTION", "SECTION",
			"HISTORY", "*TOLERANCE*");

	private static final List<String> GEOM_FUNCTION_NAMES = sorted(GEOM_FUNCTIONS);

	/**
	 * The names exported by the {@code tokenizer} package (the byte-level and
	 * SentencePiece BPE tokenizers published language models ship with), implemented in
	 * {@code tokenizers.lisp} (see {@code TokenizersLibrary}). Plain strings, like
	 * {@code linalg}: they exist only as Lisp-source defuns, and the record they pass
	 * around is an internal {@code tokenizer::%tk} defstruct, so no type name has to
	 * resolve here.
	 */
	private static final Set<String> TOKENIZER_FUNCTIONS = Set.of("MAKE-BPE", "MAKE-SENTENCEPIECE", "ENCODE", "DECODE",
			"DECODE-BYTES", "PRE-TOKENIZE", "TOKEN-STRING", "TOKEN-ID", "VOCABULARY-SIZE", "BOS-ID", "EOS-ID");

	private static final List<String> TOKENIZER_FUNCTION_NAMES = sorted(TOKENIZER_FUNCTIONS);

	/**
	 * The names exported by the {@code metal} package (a Metal drawing surface on an
	 * {@code appkit} window), implemented in {@code metal.lisp} (see
	 * {@code MetalLibrary}). Plain strings, like {@code appkit}: they exist only as
	 * Lisp-source definitions. {@code context} is the CLOS class the surface hands back
	 * and the constants are the enum members a drawing PROGRAM names -- the primitive it
	 * draws and the pipeline state it configures. The pixel formats, load/store actions,
	 * blend factors and storage modes stay internal ({@code metal::}), because they are
	 * {@code attach}/{@code pipeline}/{@code frame}'s own business rather than a decision
	 * a caller makes.
	 */
	private static final Set<String> METAL_FUNCTIONS = Set.of("CONTEXT", "ATTACH", "OFFSCREEN", "PIXELS", "DEVICE",
			"LAYER", "QUEUE", "LIBRARY", "PIPELINE", "DEPTH-STATE", "FLOATS", "BUFFER", "SHARED-BUFFER", "UPLOAD",
			"UNIFORM", "FRAME", "RUN", "RESIZE", "SET-CLEAR-COLOR", "+POINT+", "+LINE+", "+TRIANGLE+",
			"+TRIANGLE-STRIP+", "+CULL-NONE+", "+CULL-FRONT+", "+CULL-BACK+", "+WINDING-CLOCKWISE+",
			"+WINDING-COUNTER-CLOCKWISE+", "+COMPARE-LESS+", "+COMPARE-ALWAYS+");

	private static final List<String> METAL_FUNCTION_NAMES = sorted(METAL_FUNCTIONS);

	/**
	 * The names exported by the {@code scene} package (a 3-D viewer for {@code geom}
	 * solids over {@code metal}), implemented in {@code scene.lisp} (see
	 * {@code SceneLibrary}). {@code viewer-state} is the CLOS class a viewer is an
	 * instance of, registered here for the same reason geom's class names are -- a
	 * {@code (typep x 'scene:viewer-state)} spelling has to resolve.
	 */
	private static final Set<String> SCENE_FUNCTIONS = Set.of("VIEWER", "OFFSCREEN", "SNAPSHOT", "VIEWER-STATE",
			"WINDOW-OF", "CONTEXT-OF", "ADD", "DROP", "CLEAR", "CONTENTS", "FIT", "CAMERA", "GRID", "GRID-COLOR",
			"BACKGROUND", "SHADING", "AXES", "RAY", "ON-CLICK", "REFRESH", "ANIMATE", "WAIT");

	private static final List<String> SCENE_FUNCTION_NAMES = sorted(SCENE_FUNCTIONS);

	/**
	 * The functions exported by the {@code usocket} package (a usocket-compatible shim
	 * over the {@code rontolisp:tcp-*} built-ins), implemented in {@code usocket.lisp}
	 * (see {@code UsocketLibrary}). Plain strings, like {@code linalg}: no evaluator or
	 * compiler dispatches on them (only the {@code with-*} macros below are dispatched
	 * on, by their qualified names).
	 */
	private static final Set<String> USOCKET_FUNCTIONS = Set.of(LispNames.USOCKET_SOCKET_CONNECT, "SOCKET-LISTEN",
			"SOCKET-ACCEPT", "SOCKET-CLOSE", "SOCKET-STREAM", "SOCKET-OPTION", "WAIT-FOR-INPUT", "GET-LOCAL-PORT",
			"GET-LOCAL-ADDRESS", "GET-LOCAL-NAME", "GET-PEER-PORT", "GET-PEER-ADDRESS", "GET-PEER-NAME",
			LispNames.USOCKET_HOST_TO_HOSTNAME, LispNames.USOCKET_GET_HOST_BY_NAME);

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

	/**
	 * The symbols exported by the {@code closer-mop} shim package
	 * ({@code closer-mop.lisp}, see {@code eval.ShimLibraries}) -- also the overlay half
	 * of the {@code closer-common-lisp} re-export package.
	 */
	private static final Set<String> CLOSER_MOP_EXTERNALS = Set.of(LispNames.CLASS_SLOTS, LispNames.ENSURE_FINALIZED,
			LispNames.CLASSP, LispNames.CLASS_NAME, LispNames.CLASS_DIRECT_SUPERCLASSES, LispNames.CLASS_FINALIZED_P,
			LispNames.SLOT_DEFINITION_NAME, LispNames.SLOT_DEFINITION_INITARGS, LispNames.SLOT_DEFINITION_TYPE,
			LispNames.COMPUTE_SLOTS, LispNames.GENERIC_FUNCTION_LAMBDA_LIST,
			// The metaclass protocol generics (system defaults in
			// macro/mop-protocol.lisp)
			// and the two slot-definition base-class names a user metaclass protocol
			// subclasses -- MOP names, not CL symbols, so they resolve only through this
			// package (closer-common-lisp re-exports them).
			LispNames.VALIDATE_SUPERCLASS, LispNames.DIRECT_SLOT_DEFINITION_CLASS,
			LispNames.EFFECTIVE_SLOT_DEFINITION_CLASS, LispNames.COMPUTE_EFFECTIVE_SLOT_DEFINITION,
			LispNames.FINALIZE_INHERITANCE, LispNames.ENSURE_CLASS_USING_CLASS,
			ClosRegistry.STANDARD_DIRECT_SLOT_DEFINITION_NAME, ClosRegistry.STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME,
			// The mito-era accessors: readers/initfunction on slot
			// definitions, direct slots/subclasses on class metaobjects.
			LispNames.SLOT_DEFINITION_READERS, LispNames.SLOT_DEFINITION_INITFUNCTION, LispNames.CLASS_DIRECT_SLOTS,
			LispNames.CLASS_DIRECT_SUBCLASSES);

	private static final List<String> USOCKET_FUNCTION_NAMES = sorted(USOCKET_FUNCTIONS);

	private static final List<String> CL_FUNCTION_NAMES = sorted(CL_FUNCTIONS);

	private static final Set<String> SPECIAL_OPERATOR_NAMES = union(CL_SPECIAL_FORMS, CL_MACROS);

	/**
	 * The 25 ANSI SPECIAL OPERATORS -- the only names {@code special-operator-p} answers
	 * t for. Deliberately not the same set as {@link #SPECIAL_OPERATOR_NAMES}: rontolisp
	 * implements plenty of CL MACROS as special forms of its own ({@code defun},
	 * {@code handler-case}, {@code dolist}, ...), and a caller of
	 * {@code special-operator-p} only ever asks "can I {@code apply} this" -- those names
	 * answer through {@code macro-function} instead, exactly as in CL.
	 */
	private static final Set<String> ANSI_SPECIAL_OPERATORS = Set.of(LispNames.BLOCK, LispNames.CATCH,
			LispNames.EVAL_WHEN, LispNames.FLET, LispNames.FUNCTION, LispNames.GO, LispNames.IF, LispNames.LABELS,
			LispNames.LET, LispNames.LET_STAR, LispNames.LOAD_TIME_VALUE, LispNames.LOCALLY, LispNames.MACROLET,
			LispNames.MULTIPLE_VALUE_CALL, LispNames.MULTIPLE_VALUE_PROG1, LispNames.PROGN, LispNames.PROGV,
			LispNames.QUOTE, LispNames.RETURN_FROM, LispNames.SETQ, LispNames.SYMBOL_MACROLET, LispNames.TAGBODY,
			LispNames.THE, LispNames.THROW, LispNames.UNWIND_PROTECT);

	/**
	 * The operators {@code macro-function} answers NIL for: the 25 ANSI special operators
	 * plus {@code while}. {@code while} is rontolisp's OWN extension, not a CL name at
	 * all, and it only sits in the {@code cl} package because that is where the built-in
	 * operators live -- so claiming a macro function for it answers a question about a
	 * symbol CL does not have. A code walker asking "is this head a macro I should
	 * expand?" about a library's own {@code while} then gets a yes and refuses to walk
	 * its own clause: iterate's `(iter ... (while test))` warned "a macro that won't
	 * expand" and looped forever, because the clause survived into the emitted tagbody
	 * instead of becoming its exit test.
	 */
	private static final Set<String> NO_MACRO_FUNCTION = union(ANSI_SPECIAL_OPERATORS, Set.of(LispNames.WHILE));

	/**
	 * Returns the operators {@code macro-function} must answer nil for -- the ANSI
	 * special operators plus rontolisp's own {@code while}.
	 * @return the names with no macro function
	 */
	public static Set<String> namesWithoutMacroFunction() {
		return NO_MACRO_FUNCTION;
	}

	/**
	 * The names {@code macro-function} answers a macro function for, before the program's
	 * own {@code defmacro}s are added: every operator with no function value that is not
	 * in {@link #NO_MACRO_FUNCTION}. That includes the {@code cl} macros the expander
	 * dispatches on AND the CL macros rontolisp happens to implement as special forms --
	 * the two are indistinguishable to a caller, and both answers are "you cannot apply
	 * this name".
	 */
	private static final List<String> RUNTIME_MACRO_NAMES = SPECIAL_OPERATOR_NAMES.stream()
		.filter(name -> !NO_MACRO_FUNCTION.contains(name))
		.sorted()
		.toList();

	private final Map<String, LispPackage> packages = new HashMap<>();

	/**
	 * The built-in package nicknames, mapping each nickname to the canonical package
	 * name: the standard Common Lisp names ({@code common-lisp} for {@code cl},
	 * {@code common-lisp-user} for {@code cl-user}) so portable {@code (:use
	 * #:common-lisp)} clauses resolve, plus the shorthands {@code rl} for
	 * {@code rontolisp}, {@code la} for {@code linalg}, {@code flex} for
	 * {@code flexi-streams} (the spelling smart-buffer and http-body use) and
	 * {@code quicklisp} for {@code ql}. Static (unlike user
	 * {@code defpackage :nicknames}) so {@link #splitQualified} can normalize built-in
	 * qualifiers for the compile-path pre-passes that scan the program before package
	 * resolution runs.
	 */
	private static final Map<String, String> BUILTIN_NICKNAMES = Map.ofEntries(
			Map.entry("COMMON-LISP", LispNames.CL_PKG), Map.entry("COMMON-LISP-USER", LispNames.CL_USER_PKG),
			Map.entry("RL", LispNames.RONTOLISP_PKG), Map.entry("LA", LispNames.LINALG_PKG),
			Map.entry("QUICKLISP", LispNames.QL_PKG), Map.entry("C2MOP", LispNames.CLOSER_MOP_PKG),
			Map.entry("C2CL", LispNames.CLOSER_COMMON_LISP_PKG),
			Map.entry("FLOAT-FEATURES", LispNames.FLOAT_FEATURES_PKG), Map.entry("BT", LispNames.BORDEAUX_THREADS_PKG),
			Map.entry("BORDEAUX-THREADS-2", LispNames.BT2_PKG), Map.entry("CLTL2", LispNames.TRIVIAL_CLTL2_PKG),
			Map.entry("PAX", LispNames.MGL_PAX_PKG), Map.entry("TG", LispNames.TRIVIAL_GARBAGE_PKG),
			Map.entry("FLEX", LispNames.FLEXI_STREAMS_PKG));

	/**
	 * Package nicknames, mapping each nickname to the canonical package name. Seeded with
	 * {@link #BUILTIN_NICKNAMES}; {@code defpackage :nicknames} adds more.
	 */
	private final Map<String, String> nicknames = new HashMap<>(BUILTIN_NICKNAMES);

	/**
	 * The canonical names of the packages the constructor seeds (plus {@code keyword},
	 * the designator of the keyword package accepted by {@code intern}). Kept in sync
	 * with the constructor by hand -- except the 15 uiop sub-packages, which come from
	 * {@link UiopExports} because the constructor seeds them from the same inventory;
	 * used by {@link #isBuiltinPackageName} for the upcase reader mode's canonical fold,
	 * which must not depend on a registry instance.
	 */
	private static final Set<String> BUILTIN_PACKAGE_NAMES = union(
			Set.of(LispNames.CL_PKG, LispNames.CL_USER_PKG, LispNames.RONTOLISP_PKG, LispNames.LINALG_PKG,
					LispNames.TORCH_PKG, LispNames.VEC_PKG, LispNames.USOCKET_PKG, LispNames.JAVA_PKG,
					LispNames.OBJC_PKG, LispNames.APPKIT_PKG, LispNames.GEOM_PKG, LispNames.TOKENIZER_PKG,
					LispNames.METAL_PKG, LispNames.SCENE_PKG, LispNames.FFI_PKG, LispNames.ASDF_PKG, LispNames.QL_PKG,
					LispNames.UIOP_PKG, LispNames.CLOSER_MOP_PKG, LispNames.CLOSER_COMMON_LISP_PKG,
					LispNames.FLEXI_STREAMS_PKG, LispNames.FLOAT_FEATURES_PKG, LispNames.TRIVIAL_GRAY_STREAMS_PKG,
					LispNames.BORDEAUX_THREADS_PKG, LispNames.BT2_PKG, LispNames.BABEL_PKG,
					LispNames.BABEL_ENCODINGS_PKG, LispNames.SWANK_PKG, LispNames.TRIVIAL_CLTL2_PKG,
					LispNames.MGL_PAX_PKG, LispNames.TRIVIAL_GARBAGE_PKG, LispNames.CL_SSL_PKG, "KEYWORD"),
			Set.copyOf(UiopExports.subPackages()));

	/**
	 * Creates a registry seeded with the built-in packages.
	 */
	public PackageRegistry() {
		define(new LispPackage(LispNames.CL_PKG, List.of(), CL_SYMBOLS, CL_EXTERNALS));
		// cl-user exports nothing, like the Common Lisp COMMON-LISP-USER package: its
		// symbols are reachable as cl-user::name, never cl-user:name.
		define(new LispPackage(LispNames.CL_USER_PKG, List.of(LispNames.CL_PKG), new HashSet<>(), Set.of()));
		// Its canonical spelling is rontolisp; rl is a built-in nickname.
		Set<String> rontolispExternals = new HashSet<>(Set.of(LispNames.VERSION, LispNames.FETCH, LispNames.AWAIT,
				LispNames.ASYNC, LispNames.ASYNC_DEFUN, LispNames.ASYNC_LAMBDA, LispNames.FUTUREP,
				LispNames.ASYNC_STREAMP, LispNames.MAKE_STREAM, LispNames.STREAM_READ, LispNames.STREAM_WRITE,
				LispNames.STREAM_CLOSE, LispNames.READ_ALL, LispNames.WAIT_FOR, LispNames.THEN, LispNames.THEN_STAR,
				LispNames.CATCH, LispNames.FINALLY, LispNames.JSON_PARSE, LispNames.JSON_STRINGIFY,
				LispNames.PLIST_HASH_TABLE, LispNames.HASH_TABLE_PLIST, LispNames.ALIST_HASH_TABLE,
				LispNames.HASH_TABLE_ALIST, LispNames.ALIST_PLIST, LispNames.PLIST_ALIST, LispNames.URL_DECODE,
				LispNames.URL_ENCODE, LispNames.QUERY_PARAMS, LispNames.QUERY_PARAM, LispNames.URL_PATH,
				LispNames.URL_QUERY, LispNames.WASM_EXPORT, LispNames.JVM_EXPORT, LispNames.WASM_IMPORT,
				LispNames.WIT_EXPORT, LispNames.WIT_IMPORT, LispNames.WIT_PROVIDE, LispNames.WIT_ERROR,
				LispNames.WIT_ERROR_PAYLOAD, LispNames.WITH_ARENA, LispNames.MAKE_MUTEX, LispNames.MUTEX_ACQUIRE,
				LispNames.MUTEX_RELEASE, LispNames.WITH_MUTEX, LispNames.HTTP_HANDLER, LispNames.TCP_CONNECT,
				LispNames.TCP_LISTEN, LispNames.TCP_ACCEPT, LispNames.TCP_LOCAL_PORT, LispNames.TCP_LOCAL_ADDRESS,
				LispNames.TCP_PEER_ADDRESS, LispNames.TCP_PEER_PORT, LispNames.TCP_SET_TIMEOUT, LispNames.TLS_CONNECT,
				LispNames.TLS_LISTEN, LispNames.TLS_LISTEN_PEM, LispNames.TLS_LISTEN_P12, LispNames.TLS_UPGRADE,
				LispNames.RANDOM_BYTES, LispNames.MAKE_THREAD, LispNames.JOIN_THREAD, LispNames.THREADP,
				LispNames.THREAD_ALIVE_P, LispNames.DESTROY_THREAD, LispNames.CURRENT_THREAD,
				// Read-time source literals (reader.LispReader), not functions.
				LispNames.CURRENT_FILE, LispNames.CURRENT_LINE,
				// rontolisp's own Gray-stream extension
				// (eval.GrayStreamsLibrary).
				LispNames.GRAY_CHAR_OUTPUT_STREAM, LispNames.GRAY_CHAR_INPUT_STREAM, LispNames.GRAY_STREAM_WRITE_CHAR,
				LispNames.GRAY_STREAM_WRITE_STRING, LispNames.GRAY_FUNDAMENTAL_STREAM, LispNames.GRAY_INPUT_STREAM,
				LispNames.GRAY_OUTPUT_STREAM, LispNames.GRAY_BINARY_INPUT_STREAM, LispNames.GRAY_BINARY_OUTPUT_STREAM,
				LispNames.GRAY_STREAM_WRITE_BYTE, LispNames.GRAY_STREAM_READ_BYTE, LispNames.GRAY_STREAM_READ_CHAR,
				LispNames.GRAY_STREAM_UNREAD_CHAR, LispNames.GRAY_STREAM_READ_LINE, LispNames.GRAY_STREAM_LISTEN,
				LispNames.GRAY_STREAM_READ_CHAR_NO_HANG, LispNames.GRAY_STREAM_PEEK_CHAR,
				LispNames.GRAY_STREAM_READ_SEQUENCE, LispNames.GRAY_STREAM_WRITE_SEQUENCE,
				LispNames.GRAY_STREAM_LINE_COLUMN, LispNames.GRAY_STREAM_START_LINE_P, LispNames.GRAY_STREAM_TERPRI,
				LispNames.GRAY_STREAM_FRESH_LINE, LispNames.GRAY_STREAM_ADVANCE_TO_COLUMN,
				LispNames.GRAY_STREAM_FORCE_OUTPUT, LispNames.GRAY_STREAM_FINISH_OUTPUT,
				LispNames.GRAY_STREAM_CLEAR_OUTPUT, LispNames.GRAY_STREAM_FILE_POSITION,
				// The IEEE binary16 scalar pair and the bulk widen/narrow over packed
				// float arrays (eval.FloatBitsWidening), .todo/671.
				LispNames.FLOAT16_BITS, LispNames.BITS_FLOAT16, LispNames.WIDEN_FLOAT_BITS,
				LispNames.NARROW_FLOAT_BITS));
		Set<String> rontolispSymbols = new HashSet<>(rontolispExternals);
		// Internal: the stoppable HTTP server seam behind the clack-handler-rontolisp
		// shim, spelled rontolisp::%http-server-* by its call sites. Owned by the
		// package rather than left to the resolver's tolerance for an unknown ::
		// member.
		rontolispSymbols.add(LispNames.HTTP_SERVER_START);
		rontolispSymbols.add(LispNames.HTTP_SERVER_JOIN);
		rontolispSymbols.add(LispNames.HTTP_SERVER_STOP);
		rontolispSymbols.add(LispNames.HTTP_SERVER_PORT);
		define(new LispPackage(LispNames.RONTOLISP_PKG, List.of(), rontolispSymbols, Set.copyOf(rontolispExternals)));
		// numpy-style vector/matrix operations, implemented once in linalg.lisp and
		// spliced/loaded on demand (LinalgLibrary). Does not use cl; every function
		// is external. Its canonical spelling is linalg; la is a built-in nickname.
		define(new LispPackage(LispNames.LINALG_PKG, List.of(), new HashSet<>(LINALG_FUNCTIONS)));
		// A PyTorch-style tensor with reverse-mode autograd over the linalg kernels,
		// implemented once in torch.lisp and spliced/loaded on demand (TorchLibrary).
		// Does not use cl; every registered name is external.
		define(new LispPackage(LispNames.TORCH_PKG, List.of(), new HashSet<>(TORCH_FUNCTIONS)));
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
		// Interpreter-only Objective-C interop through the foreign function API (no
		// reflection, so it runs in the native binary too). Does not use cl; its values
		// (LispObjcObject) cannot be lowered by any compiler.
		define(new LispPackage(LispNames.OBJC_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.OBJC_CLASS, LispNames.OBJC_SEND, LispNames.OBJC_DEFINE_CLASS,
						LispNames.OBJC_ON_MAIN, LispNames.OBJC_STRING, LispNames.OBJC_DATA, LispNames.OBJC_BYTES,
						LispNames.OBJC_ADDRESS, LispNames.OBJC_OBJECTP))));
		// A Cocoa widget layer over objc:, implemented once in appkit.lisp and loaded on
		// demand (AppKitLibrary). Does not use cl; every function is external.
		define(new LispPackage(LispNames.APPKIT_PKG, List.of(), new HashSet<>(APPKIT_FUNCTIONS)));
		// Solid modeling over the linalg kernels, implemented once in geom.lisp and
		// spliced/loaded on demand (GeomLibrary). Backend-independent -- it reaches for
		// nothing but linalg -- so unlike appkit it runs everywhere. Does not use cl;
		// every registered name is external.
		define(new LispPackage(LispNames.GEOM_PKG, List.of(), new HashSet<>(GEOM_FUNCTIONS)));
		// The BPE tokenizers a published language model ships with, implemented once in
		// tokenizers.lisp and spliced/loaded on demand (TokenizersLibrary). Reaches for
		// nothing but cl -- the vocabulary is an argument, not a file it opens -- so it
		// runs everywhere geom does. Does not use cl; every registered name is external.
		define(new LispPackage(LispNames.TOKENIZER_PKG, List.of(), new HashSet<>(TOKENIZER_FUNCTIONS)));
		// A Metal drawing surface over objc:, implemented once in metal.lisp and
		// spliced/loaded on demand (MetalLibrary). macOS only, like appkit; usable
		// WITHOUT geom or scene, which is what four examples do. Does not use cl.
		define(new LispPackage(LispNames.METAL_PKG, List.of(), new HashSet<>(METAL_FUNCTIONS)));
		// A 3-D viewer for geom solids over metal, implemented once in scene.lisp and
		// spliced/loaded on demand (SceneLibrary). macOS only. Does not use cl.
		define(new LispPackage(LispNames.SCENE_PKG, List.of(), new HashSet<>(SCENE_FUNCTIONS)));
		// C interop through the foreign function API (no reflection, so it runs in the
		// native binary too, against the registered shape grid): the foreign primitives
		// CFFI's backend stands on (eval.FfiInterop over am.ik.ffi). Does not use cl;
		// its values (LispForeignPointer) cannot be lowered by any WASM backend, and the
		// JVM class output carries the binding as an embedded blob.
		define(new LispPackage(LispNames.FFI_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.FFI_OPEN, LispNames.FFI_SYMBOL, LispNames.FFI_CALL,
						LispNames.FFI_CALLBACK, LispNames.FFI_ALLOC, LispNames.FFI_FREE, LispNames.FFI_PEEK,
						LispNames.FFI_POKE, LispNames.FFI_SIZE, LispNames.FFI_ALIGN, LispNames.FFI_POINTERP,
						LispNames.FFI_ADDRESS, LispNames.FFI_ERRNO, LispNames.FFI_APPLY_CALL))));
		// A limited, API-compatible subset of ASDF (system definitions parsed from .asd
		// files as plain data -- see eval.AsdfSystems; the runtime component metaobject
		// family lives in Lisp source, eval.AsdfRuntimeLibrary / asdf.lisp). Does not
		// use cl; every registered symbol is external.
		define(new LispPackage(LispNames.ASDF_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.DEFSYSTEM, LispNames.LOAD_SYSTEM, LispNames.FIND_SYSTEM,
						LispNames.TEST_SYSTEM, LispNames.SYSTEM_SOURCE_DIRECTORY, LispNames.SYSTEM_RELATIVE_PATHNAME,
						LispNames.COMPONENT_PATHNAME, LispNames.REGISTERED_SYSTEMS, LispNames.ASDF_USER_CACHE,
						// The component metaobject family (asdf.lisp): the classes and
						// their readers, real CLOS classes on every backend so typecase /
						// typep / defmethod specializers work (rove's run-system reads
						// the whole component model).
						LispNames.COMPONENT, LispNames.CHILD_COMPONENT, LispNames.PARENT_COMPONENT, LispNames.MODULE,
						"SYSTEM", LispNames.PACKAGE_INFERRED_SYSTEM, LispNames.SOURCE_FILE, LispNames.CL_SOURCE_FILE,
						LispNames.STATIC_FILE, LispNames.COMPONENT_NAME, LispNames.COMPONENT_VERSION,
						LispNames.COMPONENT_CHILDREN, LispNames.COMPONENT_SIDEWAY_DEPENDENCIES,
						LispNames.COMPONENT_PARENT, LispNames.COMPONENT_SYSTEM,
						// The missing-component CONDITION name and the retry RESTART
						// name, external in real ASDF and resolve-only here (never
						// defined): dbi's with-autoload-on-missing handler-binds
						// asdf:missing-component / invokes asdf:retry around its runtime
						// load-system call -- dead code here, since a missing system is
						// a hard error, never a signaled missing-component.
						"MISSING-COMPONENT", "RETRY",
						// operate (and the load-op it names) is the general CLOS entry
						// point real ASDF's load-system is defined in terms of --
						// external and resolve-only here too, for the same reason:
						// cffi's define-foreign-library calls (asdf:operate
						// 'asdf:load-op 'cffi-libffi) inside a restart body that never
						// runs here, and the READ must not fail on an unknown external.
						"OPERATE", "LOAD-OP"))));
		// A limited, API-compatible subset of Quicklisp: ql:quickload downloads a system
		// (and its dependencies) from the real Quicklisp distribution into a local cache
		// and then defers to the asdf subset (see eval.QuicklispClient). Its canonical
		// spelling is ql; quicklisp is a built-in nickname. Does not use cl; the symbol
		// is external.
		define(new LispPackage(LispNames.QL_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.QUICKLOAD, LispNames.UPDATE_DIST))));
		// ql-dist, where Quicklisp keeps the distribution machinery: the one member a
		// program writes is install-dist, which adds a Quicklisp-format distribution
		// (Ultralisp, say) to the dists ql:quickload downloads from. Does not use cl.
		define(new LispPackage(LispNames.QL_DIST_PKG, List.of(), new HashSet<>(Set.of(LispNames.INSTALL_DIST))));
		// uiop, as upstream builds it: `uiop` IS `uiop/driver`, a re-export of 15
		// sub-packages, and a library may name either spelling --
		// lack-middleware-backtrace
		// spells (:import-from :uiop/image :print-condition-backtrace), which is a READ
		// error when the package is absent. Every sub-package owns the names its OWN rows
		// in the inventory list (UiopExports, from the checked-in uiop-exports.txt); a
		// name
		// a second sub-package also exports is an import redirect to the home one
		// (uiop/backward-driver re-exports five uiop/configuration names), and so is
		// every
		// member of uiop itself. Both spellings therefore name the SAME symbol rather
		// than
		// two functions with one member name. What is DEFINED behind those names is
		// eval.UiopLibrary's business -- see .kb/uiop.md.
		for (String subPackage : UiopExports.subPackages()) {
			Set<String> externals = UiopExports.externals(subPackage);
			Map<String, String> redirects = new HashMap<>();
			for (String name : externals) {
				String home = UiopExports.homePackage(name);
				if (home != null && !home.equals(subPackage)) {
					redirects.put(name, home);
				}
			}
			define(new LispPackage(subPackage, List.of(), new HashSet<>(externals), Set.copyOf(externals),
					Map.copyOf(redirects)));
		}
		Map<String, String> uiopImports = new HashMap<>(UiopExports.homePackages());
		// namestring is IMPORTED from cl rather than owned: upstream's uiop/driver
		// inherits CL's through (:use :uiop/common-lisp), rontolisp's cl:namestring is a
		// real prelude function, and two same-member symbols would be two functions of
		// which only one is defined. Deliberately EXTERNAL here, where upstream leaves it
		// merely accessible.
		uiopImports.put(LispNames.NAMESTRING, LispNames.CL_PKG);
		Set<String> uiopExternals = new HashSet<>(uiopImports.keySet());
		// when-let / when-let* are alexandria's names, not uiop's: real uiop exports
		// if-let only. Kept as rontolisp EXTRAS (owned by uiop, hence no redirect) rather
		// than dropped, because programs already spell them.
		uiopExternals.add(LispNames.WHEN_LET);
		uiopExternals.add(LispNames.WHEN_LET_STAR);
		Set<String> uiopSymbols = new HashSet<>(uiopExternals);
		define(new LispPackage(LispNames.UIOP_PKG, List.of(), uiopSymbols, Set.copyOf(uiopExternals),
				Map.copyOf(uiopImports)));
		// The dependency-shim packages behind the built-in ASDF systems of the same
		// names (see eval.ShimLibraries): closer-mop (nickname c2mop),
		// flexi-streams, org.shirakumo.float-features (nickname float-features) and
		// trivial-gray-streams.
		define(new LispPackage(LispNames.CLOSER_MOP_PKG, List.of(), new HashSet<>(CLOSER_MOP_EXTERNALS)));
		// closer-common-lisp (nickname c2cl): the flat re-export of the cl externals
		// overlaid with the closer-mop externals (closer-mop wins collisions, per the
		// upstream package of the same name). Resolution is textual, so every member is
		// recorded as an IMPORT redirecting to its home package -- a qualified
		// closer-common-lisp:class-slots resolves to closer-mop:class-slots and
		// closer-common-lisp:mapcar to the bare cl name -- and using this package
		// implies using cl (see PackageResolver.impliedUses).
		Map<String, String> c2clImports = new HashMap<>();
		for (String name : CL_EXTERNALS) {
			c2clImports.put(name, LispNames.CL_PKG);
		}
		for (String name : CLOSER_MOP_EXTERNALS) {
			c2clImports.put(name, LispNames.CLOSER_MOP_PKG);
		}
		define(new LispPackage(LispNames.CLOSER_COMMON_LISP_PKG, List.of(LispNames.CL_PKG),
				Set.copyOf(c2clImports.keySet()), Set.copyOf(c2clImports.keySet()), Map.copyOf(c2clImports)));
		// flexi-streams: the encoder/decoder pair and the wrapper are external, and so
		// is the in-memory input constructor plus the vector-stream CLASS -- http-body
		// spells (typep s 'flex:vector-stream) with a single colon. The three slot
		// accessors stay INTERNAL, as upstream, which is why the same file reaches for
		// flex::vector-stream-vector with a double one. The flexi-stream class and its
		// five accessors are external for the same reason: upstream cl+ssl spells
		// flexi-streams:flexi-stream / flexi-streams:flexi-stream-stream with one colon.
		Set<String> flexiExternals = Set.of(LispNames.MAKE_FLEXI_STREAM, LispNames.STRING_TO_OCTETS,
				LispNames.OCTETS_TO_STRING, LispNames.VECTOR_STREAM, LispNames.MAKE_IN_MEMORY_INPUT_STREAM,
				LispNames.MAKE_IN_MEMORY_OUTPUT_STREAM, LispNames.GET_OUTPUT_STREAM_SEQUENCE, LispNames.FLEXI_STREAM,
				LispNames.FLEXI_STREAM_STREAM, LispNames.FLEXI_STREAM_EXTERNAL_FORMAT,
				LispNames.FLEXI_STREAM_ELEMENT_TYPE, LispNames.FLEXI_STREAM_POSITION, LispNames.FLEXI_STREAM_BOUND);
		Set<String> flexiSymbols = new HashSet<>(flexiExternals);
		flexiSymbols.addAll(Set.of(LispNames.VECTOR_INPUT_STREAM, LispNames.VECTOR_STREAM_VECTOR,
				LispNames.VECTOR_STREAM_INDEX, LispNames.VECTOR_STREAM_END, LispNames.VECTOR_OUTPUT_STREAM));
		define(new LispPackage(LispNames.FLEXI_STREAMS_PKG, List.of(), flexiSymbols, flexiExternals));
		define(new LispPackage(LispNames.FLOAT_FEATURES_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.BITS_DOUBLE_FLOAT, LispNames.DOUBLE_FLOAT_BITS,
						LispNames.SINGLE_FLOAT_BITS, LispNames.BITS_SINGLE_FLOAT))));
		define(new LispPackage(LispNames.TRIVIAL_GRAY_STREAMS_PKG, List.of(), new HashSet<>(Set.of(
				LispNames.GRAY_CHAR_OUTPUT_STREAM, LispNames.GRAY_CHAR_INPUT_STREAM, LispNames.GRAY_STREAM_WRITE_CHAR,
				LispNames.GRAY_STREAM_WRITE_STRING, LispNames.GRAY_FUNDAMENTAL_STREAM, LispNames.GRAY_INPUT_STREAM,
				LispNames.GRAY_OUTPUT_STREAM, LispNames.GRAY_BINARY_INPUT_STREAM, LispNames.GRAY_BINARY_OUTPUT_STREAM,
				LispNames.GRAY_STREAM_MIXIN, LispNames.GRAY_STREAM_WRITE_BYTE, LispNames.GRAY_STREAM_READ_BYTE,
				LispNames.GRAY_STREAM_READ_CHAR, LispNames.GRAY_STREAM_UNREAD_CHAR, LispNames.GRAY_STREAM_READ_LINE,
				LispNames.GRAY_STREAM_LISTEN, LispNames.GRAY_STREAM_READ_SEQUENCE, LispNames.GRAY_STREAM_WRITE_SEQUENCE,
				LispNames.GRAY_STREAM_LINE_COLUMN, LispNames.GRAY_STREAM_START_LINE_P, LispNames.GRAY_STREAM_TERPRI,
				LispNames.GRAY_STREAM_FRESH_LINE, LispNames.GRAY_STREAM_ADVANCE_TO_COLUMN,
				LispNames.GRAY_STREAM_FORCE_OUTPUT, LispNames.GRAY_STREAM_FINISH_OUTPUT,
				LispNames.GRAY_STREAM_CLEAR_OUTPUT, LispNames.GRAY_STREAM_FILE_POSITION))));
		// bordeaux-threads (nickname bt) + bt2 (nickname bordeaux-threads-2, mirroring
		// upstream's apiv2/pkgdcl.lisp): one shim system (bordeaux-threads.lisp,
		// eval.ShimLibraries) providing both API namespaces. The locking subset rides the
		// rontolisp:*-mutex primitives and stays home in the v1 package (with-lock-held
		// is a built-in LispMacroExpander expansion dispatched on its qualified name, the
		// usocket:with-* pattern); thread creation rides the rontolisp:make-thread
		// primitive (interpreter + JVM; the WASM entry points signal at call time) and is
		// home in bt2, the modern API clack's handler.lisp drives. Each package imports
		// the other's half, so both spellings of every name resolve to the ONE defining
		// symbol (the closer-common-lisp / uiop-image redirect precedent).
		Map<String, String> btThreadImports = Map.of(LispNames.MAKE_THREAD, LispNames.BT2_PKG, LispNames.JOIN_THREAD,
				LispNames.BT2_PKG, LispNames.THREADP, LispNames.BT2_PKG, LispNames.THREAD_ALIVE_P, LispNames.BT2_PKG,
				LispNames.DESTROY_THREAD, LispNames.BT2_PKG, LispNames.DEFAULT_SPECIAL_BINDINGS, LispNames.BT2_PKG,
				LispNames.CURRENT_THREAD, LispNames.BT2_PKG);
		Set<String> btV1Externals = new HashSet<>(
				Set.of(LispNames.MAKE_LOCK, LispNames.ACQUIRE_LOCK, LispNames.RELEASE_LOCK, LispNames.WITH_LOCK_HELD,
						LispNames.SUPPORTS_THREADS_P, LispNames.MAKE_THREAD, LispNames.JOIN_THREAD, LispNames.THREADP,
						LispNames.THREAD_ALIVE_P, LispNames.DESTROY_THREAD, LispNames.DEFAULT_SPECIAL_BINDINGS,
						LispNames.CURRENT_THREAD, LispNames.MAKE_RECURSIVE_LOCK, LispNames.WITH_RECURSIVE_LOCK_HELD));
		define(new LispPackage(LispNames.BORDEAUX_THREADS_PKG, List.of(), btV1Externals, Set.copyOf(btV1Externals),
				btThreadImports));
		Map<String, String> bt2LockImports = Map.of(LispNames.MAKE_LOCK, LispNames.BORDEAUX_THREADS_PKG,
				LispNames.ACQUIRE_LOCK, LispNames.BORDEAUX_THREADS_PKG, LispNames.RELEASE_LOCK,
				LispNames.BORDEAUX_THREADS_PKG, LispNames.WITH_LOCK_HELD, LispNames.BORDEAUX_THREADS_PKG);
		Set<String> bt2Externals = new HashSet<>(Set.of(LispNames.MAKE_THREAD, LispNames.JOIN_THREAD, LispNames.THREADP,
				LispNames.THREAD_ALIVE_P, LispNames.DESTROY_THREAD, LispNames.DEFAULT_SPECIAL_BINDINGS,
				LispNames.MAKE_LOCK, LispNames.ACQUIRE_LOCK, LispNames.RELEASE_LOCK, LispNames.WITH_LOCK_HELD,
				LispNames.CURRENT_THREAD));
		Set<String> bt2Symbols = new HashSet<>(bt2Externals);
		// Internal: the shim's own :initial-bindings value-form resolver, spelled
		// bt2::resolve-binding-value by its call site. Owned by the package rather than
		// left to the resolver's tolerance for an unknown :: member.
		bt2Symbols.add(LispNames.RESOLVE_BINDING_VALUE);
		define(new LispPackage(LispNames.BT2_PKG, List.of(), bt2Symbols, Set.copyOf(bt2Externals), bt2LockImports));
		// babel + babel-encodings: the UTF-8 slice of the charset-conversion library,
		// implemented in babel.lisp (eval.ShimLibraries). Real babel carries 40+ code
		// pages; rontolisp has one character model (a character IS a code point, the
		// wire form is UTF-8), so the shim implements that codec and SIGNALS on any
		// other :encoding rather than mis-coding silently. Both packages export
		// *default-character-encoding* -- real babel's babel package inherits it by
		// :use-ing babel-encodings, and callers spell both.
		//
		// babel-encodings owns the ENCODING half: the two specials, the encoding /
		// mapping lookups, the four mapping readers a consumer that decodes
		// INCREMENTALLY calls (dexador's decoding-stream imports exactly those), and
		// the condition hierarchy the consumer's fallback path catches. The names are
		// string literals rather than LispNames constants because nothing in Java
		// refers to them -- the trivial-cltl2 precedent below.
		Set<String> babelEncodingsExternals = new HashSet<>(Set.of(LispNames.DEFAULT_CHARACTER_ENCODING,
				LispNames.LIST_CHARACTER_ENCODINGS, "*SUPPRESS-CHARACTER-CODING-ERRORS*", "GET-CHARACTER-ENCODING",
				"ENC-MAX-UNITS-PER-CHAR", "LOOKUP-MAPPING", "CODE-POINT-COUNTER", "OCTET-COUNTER", "DECODER", "ENCODER",
				"CHARACTER-CODING-ERROR", "CHARACTER-CODING-ERROR-BUFFER", "CHARACTER-CODING-ERROR-POSITION",
				"CHARACTER-CODING-ERROR-ENCODING", "CHARACTER-DECODING-ERROR", "CHARACTER-DECODING-ERROR-OCTETS",
				"CHARACTER-ENCODING-ERROR", "CHARACTER-ENCODING-ERROR-CODE", "END-OF-INPUT-IN-CHARACTER",
				"CHARACTER-OUT-OF-RANGE", "INVALID-UTF8-STARTER-BYTE", "INVALID-UTF8-CONTINUATION-BYTE",
				"OVERLONG-UTF8-SEQUENCE"));
		define(new LispPackage(LispNames.BABEL_ENCODINGS_PKG, List.of(), babelEncodingsExternals));
		Set<String> babelExternals = new HashSet<>(
				Set.of(LispNames.STRING_TO_OCTETS, LispNames.OCTETS_TO_STRING, LispNames.STRING_SIZE_IN_OCTETS,
						// The mapping TABLE and the character type live in babel proper
						// upstream, not in babel-encodings. *string-vector-mappings* is
						// INTERNAL upstream -- a defpackage :import-from reads it anyway
						// --
						// but a rontolisp :import-from resolves through the external
						// list, so
						// the shim exports it.
						// unicode-string / simple-unicode-string are upstream's
						// degenerate
						// aliases (unicode-char above is exactly character) -- external
						// upstream, and cffi's strings.lisp reads them by that spelling.
						"*STRING-VECTOR-MAPPINGS*", "UNICODE-CHAR", "UNICODE-STRING", "SIMPLE-UNICODE-STRING"));
		// Real babel :uses babel-encodings and re-exports it, so every babel-encodings
		// external must also answer to the babel: spelling -- as an IMPORT REDIRECT,
		// not an owned symbol, so babel:X and babel-encodings:X canonicalize to the
		// SAME spelling (the home package's). http-body's detect-charset defaults from
		// babel:*default-character-encoding* while the shim's defvar spells
		// babel-encodings:; dexador imports character-decoding-error from babel and
		// *suppress-character-coding-errors* from babel-encodings. Without the
		// redirect each pair is two symbols, and the babel: one is a global nothing
		// binds.
		Map<String, String> babelImports = new HashMap<>();
		for (String name : babelEncodingsExternals) {
			babelImports.put(name, LispNames.BABEL_ENCODINGS_PKG);
		}
		babelExternals.addAll(babelEncodingsExternals);
		Set<String> babelSymbols = new HashSet<>(babelExternals);
		// Internal: the shim's own encoding-name normalizer and the codec the mapping
		// readers hand out (the counters, the two coders, the one-character UTF-8
		// decode and its two octet predicates), spelled babel::name by their call
		// sites. Owned by the package rather than left to the resolver's tolerance for
		// an unknown :: member.
		babelSymbols.addAll(Set.of(LispNames.NORMALIZE_ENCODING, "%DECODING-ERROR", "%ENCODING-ERROR",
				"%COUNT-CODE-POINTS", "%COUNT-OCTETS", "%DECODE-INTO", "%ENCODE-INTO", "%UTF-8-DECODE-1",
				"%CONTINUATION-P", "%INVALID-CB-P", "STRING-GET", "STRING-SET"));
		define(new LispPackage(LispNames.BABEL_PKG, List.of(), babelSymbols, babelExternals, babelImports));
		// swank: the STUB behind the built-in ASDF system of the same name
		// (swank.lisp, eval.ShimLibraries). The real swank is SLIME's server half --
		// a remote REPL attached to a running image, which no backend can offer, and
		// whose .asd is a program the defsystem-as-data front-end cannot read at all.
		// clack's .asd hard-depends on it, so without the stub (ql:quickload "clack")
		// downloads the SLIME tarball and dies on it.
		define(new LispPackage(LispNames.SWANK_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.CREATE_SERVER, LispNames.STOP_SERVER))));
		// trivial-cltl2 (nickname cltl2): the shim behind the built-in ASDF system of
		// the same name (trivial-cltl2.lisp, eval.ShimLibraries). The real library is
		// a pure re-export of the host's CLtL2 environment API; on rontolisp every
		// implementation branch is feature-false, so loading it verbatim yields only
		// undefined names. define-declaration / declaration-information have shim
		// definitions (trivia level2 calls them); the rest of the export list resolves
		// but is an undefined-function error when called (the uiop stub convention).
		define(new LispPackage(LispNames.TRIVIAL_CLTL2_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.DEFINE_DECLARATION, LispNames.DECLARATION_INFORMATION, "COMPILER-LET",
						"VARIABLE-INFORMATION", "FUNCTION-INFORMATION", "AUGMENT-ENVIRONMENT", "PARSE-MACRO",
						"ENCLOSE"))));
		// mgl-pax (nickname pax): the STUB behind the built-in ASDF system
		// "mgl-pax-bootstrap" (mgl-pax-bootstrap.lisp, eval.ShimLibraries). Real
		// mgl-pax-bootstrap's own .asd declares :around-compile, a compile hook outside
		// the defsystem-as-data subset; trivial-utf-8 (a uuid dependency) hard-depends on
		// it and its source calls exactly these members. define-package is consumed by
		// PackageResolver.resolve like defpackage; section appears only as data inside
		// defsection bodies (unevaluated, so it needs to resolve but never to be
		// defined).
		define(new LispPackage(LispNames.MGL_PAX_PKG, List.of(), new HashSet<>(Set.of(LispNames.DEFINE_PACKAGE,
				"DEFSECTION", "SECTION", "MAKE-GITHUB-SOURCE-URI-FN", "REGISTER-DOC-IN-PAX-WORLD"))));
		// trivial-garbage (nickname tg): the no-op GC-finalizer shim behind the
		// built-in ASDF system of the same name (trivial-garbage.lisp,
		// eval.ShimLibraries) -- dbd-postgres imports the finalizer pair, and the
		// weak-table pair is what a CFFI binding keeps its per-thread state in.
		define(new LispPackage(LispNames.TRIVIAL_GARBAGE_PKG, List.of(), new HashSet<>(
				Set.of("FINALIZE", "CANCEL-FINALIZATION", "MAKE-WEAK-HASH-TABLE", "HASH-TABLE-WEAKNESS"))));
		// cl+ssl: the CLIENT-side TLS shim behind the built-in ASDF system of the same
		// name (cl-ssl.lisp, eval.ShimLibraries), over the rontolisp:tls-upgrade
		// primitive. The externals are the surface dexador (and the other
		// usocket+cl+ssl client stacks) import: make-ssl-client-stream upgrades an
		// already-connected stream; make-context / with-global-context /
		// ssl-check-verify-p carry the verify mode; ensure-initialized is a no-op;
		// use-certificate-chain-file (a client certificate, no backing) signals. The
		// global-context special stays INTERNAL, as upstream.
		Set<String> clSslExternals = Set.of("ENSURE-INITIALIZED", "MAKE-CONTEXT", "WITH-GLOBAL-CONTEXT",
				"+SSL-VERIFY-NONE+", "+SSL-VERIFY-PEER+", "SSL-CHECK-VERIFY-P", "USE-CERTIFICATE-CHAIN-FILE",
				"MAKE-SSL-CLIENT-STREAM");
		Set<String> clSslSymbols = new HashSet<>(clSslExternals);
		clSslSymbols.add("*SSL-GLOBAL-CONTEXT*");
		define(new LispPackage(LispNames.CL_SSL_PKG, List.of(), clSslSymbols, clSslExternals));
	}

	/**
	 * Returns the names of the {@code cl} functions, sorted alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> clFunctionNames() {
		return CL_FUNCTION_NAMES;
	}

	/**
	 * Returns whether the given unqualified name is a {@code cl} FUNCTION -- a standard
	 * name usable as a function value, as opposed to a macro or special operator. The
	 * distinction is what tells a redefinition that a backend can reason about
	 * ({@code (defun random ...)}) from one no dispatch could honour anywhere
	 * ({@code (defun if ...)}).
	 * @param name the canonical (upper-case, unqualified) name
	 * @return {@code true} if it names a {@code cl} function
	 */
	public static boolean isClFunctionName(String name) {
		return CL_FUNCTIONS.contains(name);
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
	 * Returns the 25 ANSI special operators -- the exact set {@code special-operator-p}
	 * answers t for.
	 * @return the ANSI special operator names
	 */
	public static Set<String> ansiSpecialOperatorNames() {
		return ANSI_SPECIAL_OPERATORS;
	}

	/**
	 * Returns the built-in names {@code macro-function} answers a macro function for
	 * (every operator with no function value except the 25 ANSI special operators),
	 * sorted alphabetically so the baked table a compiled program carries is stable.
	 * @return the sorted built-in macro names
	 */
	public static List<String> runtimeMacroNames() {
		return RUNTIME_MACRO_NAMES;
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
	 * Returns the names exported by the {@code torch} package, sorted alphabetically.
	 * @return the sorted names
	 */
	public static List<String> torchFunctionNames() {
		return TORCH_FUNCTION_NAMES;
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
	 * Returns the names of the functions exported by the {@code appkit} package, sorted
	 * alphabetically.
	 * @return the sorted function names
	 */
	public static List<String> appkitFunctionNames() {
		return APPKIT_FUNCTION_NAMES;
	}

	/**
	 * Returns the names exported by the {@code geom} package, sorted alphabetically.
	 * @return the sorted exported names
	 */
	public static List<String> geomFunctionNames() {
		return GEOM_FUNCTION_NAMES;
	}

	/**
	 * Returns the names exported by the {@code tokenizer} package, sorted alphabetically.
	 * @return the sorted exported names
	 */
	public static List<String> tokenizerFunctionNames() {
		return TOKENIZER_FUNCTION_NAMES;
	}

	/**
	 * Returns the names exported by the {@code metal} package, sorted alphabetically.
	 * @return the sorted exported names
	 */
	public static List<String> metalFunctionNames() {
		return METAL_FUNCTION_NAMES;
	}

	/**
	 * Returns the names exported by the {@code scene} package, sorted alphabetically.
	 * @return the sorted exported names
	 */
	public static List<String> sceneFunctionNames() {
		return SCENE_FUNCTION_NAMES;
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
	 * Every designator that names a registered package -- each canonical name plus every
	 * nickname pointing at one -- mapped to that package's canonical name. Backs the
	 * runtime {@code find-package} table the compile paths bake in: the compiled runtimes
	 * have no registry, so the answer for a COMPUTED designator has to be carried over
	 * from compile time.
	 * @return the designator-to-canonical-name table
	 */
	public Map<String, String> designatorTable() {
		Map<String, String> table = new HashMap<>();
		for (String name : this.packages.keySet()) {
			table.put(name, name);
		}
		this.nicknames.forEach((nickname, target) -> {
			if (this.packages.containsKey(target)) {
				table.put(nickname, target);
			}
		});
		return table;
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
		return CL_SYMBOLS.contains(name) || LispNames.isCarCdrComposition(name);
	}

	/**
	 * Returns the fixed set of {@code cl}-package symbol names (functions, macros,
	 * special forms, variables, type specifiers). Excludes the car/cdr compositions,
	 * which are a pattern rather than a set (see {@link LispNames#isCarCdrComposition}).
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
	 * Returns every spelling {@code name} can be written as: both colon spellings of a
	 * package-qualified name (external {@code pkg:member} first, then internal
	 * {@code pkg::member}), or the name alone when it is not qualified.
	 * <p>
	 * A {@link List} rather than a {@code Set} on purpose: the pruners that ask this
	 * question carry the answer around in their data model, and a {@code Set.of} of two
	 * elements iterates in a per-JVM-run order, which is the exact ingredient of the
	 * emitted-output bugs {@code .kb/emitted-output-determinism.md} records. The order
	 * here is fixed and costs nothing.
	 * @param name the symbol name, qualified or not
	 * @return the spellings, in a fixed order
	 */
	public static List<String> spellings(String name) {
		QualifiedName qn = splitQualified(name);
		if (qn == null) {
			return List.of(name);
		}
		return List.of(qualify(qn.pkg(), qn.member()), qualifyInternal(qn.pkg(), qn.member()));
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

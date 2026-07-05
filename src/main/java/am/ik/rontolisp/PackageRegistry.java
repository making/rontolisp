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
			LispNames.DEFMACRO, LispNames.DEFSTRUCT, LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT,
			LispNames.RETURN, LispNames.IN_PACKAGE, LispNames.DEFPACKAGE);

	/**
	 * The {@code cl} macros: operators expanded by {@link LispMacroExpander} that have no
	 * function value. Names that expand internally but are also usable as function values
	 * ({@code first}, {@code length}, {@code 1+}, ...) are classified as functions.
	 */
	private static final Set<String> CL_MACROS = Set.of(LispNames.COND, LispNames.CASE, LispNames.AND, LispNames.OR,
			LispNames.WHEN, LispNames.UNLESS, LispNames.DOTIMES, LispNames.SETF, LispNames.PUSH, LispNames.POP,
			LispNames.REMF, LispNames.LET_STAR, LispNames.DOLIST, LispNames.INCF, LispNames.DECF, LispNames.FORMAT,
			LispNames.WITH_OPEN_FILE, LispNames.PROG1, LispNames.DO, LispNames.DO_STAR, LispNames.PROG2,
			LispNames.PSETQ, LispNames.TYPECASE, LispNames.ECASE, LispNames.ETYPECASE, LispNames.CCASE, LispNames.ERROR,
			LispNames.TIME, LispNames.LOOP, LispNames.CHECK_TYPE, LispNames.ASSERT, LispNames.DECLARE,
			LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.THE, LispNames.EVAL_WHEN, LispNames.FLET, LispNames.LABELS,
			LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_LIST, LispNames.MULTIPLE_VALUE_CALL,
			LispNames.NTH_VALUE, LispNames.DESTRUCTURING_BIND, LispNames.WITH_OUTPUT_TO_STRING,
			LispNames.WITH_INPUT_FROM_STRING, LispNames.PUSHNEW, LispNames.DEFTYPE, LispNames.DEFINE_CONDITION,
			LispNames.DEFINE_COMPILER_MACRO, LispNames.RESTART_CASE, LispNames.MACROLET, LispNames.MAKE_CONDITION,
			LispNames.DOCUMENTATION, LispNames.COMPLEMENT, LispNames.COMPLEX);

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
			LispNames.EQ_GENERAL, LispNames.EQL, LispNames.EQUAL, LispNames.LT, LispNames.GT, LispNames.LE,
			LispNames.GE, LispNames.CONS, LispNames.CAR, LispNames.CDR, LispNames.LIST, LispNames.APPEND,
			LispNames.NTHCDR, LispNames.RPLACA, LispNames.RPLACD, LispNames.MAPCAR, LispNames.MAP, LispNames.MAPC,
			LispNames.MAPCAN, LispNames.APPLY, LispNames.SORT, LispNames.REDUCE, LispNames.EVERY, LispNames.SOME,
			LispNames.REMOVE, LispNames.REMOVE_IF, LispNames.REMOVE_IF_NOT, LispNames.NOT, LispNames.NULL,
			LispNames.ATOM, LispNames.NUMBERP, LispNames.INTEGERP, LispNames.FLOATP, LispNames.RATIONALP,
			LispNames.NUMERATOR, LispNames.DENOMINATOR, LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP,
			LispNames.CONSP, LispNames.KEYWORDP, LispNames.FLOAT, LispNames.TRUNCATE, LispNames.FLOOR,
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
			LispNames.STRING_EQUAL, LispNames.STRING_TRIM, LispNames.STRING_LEFT_TRIM, LispNames.STRING_RIGHT_TRIM,
			LispNames.OPEN, LispNames.CLOSE, LispNames.WRITE_LINE, LispNames.READ_BYTE, LispNames.WRITE_BYTE,
			LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE, LispNames.IDENTITY, LispNames.COPY_LIST,
			LispNames.NREVERSE, LispNames.MAKE_LIST, LispNames.UNION, LispNames.INTERSECTION, LispNames.SET_DIFFERENCE,
			LispNames.ADJOIN, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH,
			LispNames.INTEGER_LENGTH, LispNames.LOGBITP, LispNames.LIST_STAR, LispNames.ACONS, LispNames.ENDP,
			LispNames.ELT, LispNames.RASSOC, LispNames.PAIRLIS, LispNames.COPY_ALIST, LispNames.REVAPPEND,
			LispNames.NRECONC, LispNames.MAPLIST, LispNames.MAPCON, LispNames.NOTANY, LispNames.NOTEVERY,
			LispNames.DELETE, LispNames.DELETE_IF, LispNames.DELETE_IF_NOT, LispNames.SUBSTITUTE, LispNames.NSUBSTITUTE,
			LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME,
			LispNames.GETENV, LispNames.READ_FROM_STRING, LispNames.PARSE_INTEGER, LispNames.CHAR, LispNames.SCHAR,
			LispNames.CHAR_CODE, LispNames.CODE_CHAR, LispNames.CHAR_EQ, LispNames.CHAR_LT, LispNames.CHAR_LE,
			LispNames.CHAR_UPCASE, LispNames.CHAR_DOWNCASE, LispNames.CHARACTERP, LispNames.ALPHA_CHAR_P,
			LispNames.DIGIT_CHAR_P, LispNames.MAKE_HASH_TABLE, LispNames.GETHASH, LispNames.REMHASH, LispNames.CLRHASH,
			LispNames.HASH_TABLE_COUNT, LispNames.HASH_TABLE_P, LispNames.MAPHASH, LispNames.MAKE_ARRAY, LispNames.AREF,
			LispNames.VECTOR, LispNames.SVREF, LispNames.ARRAY_DIMENSIONS, LispNames.ARRAY_DIMENSION,
			LispNames.ARRAY_RANK, LispNames.ARRAY_TOTAL_SIZE, LispNames.ROW_MAJOR_AREF, LispNames.ARRAY_ROW_MAJOR_INDEX,
			LispNames.COERCE, LispNames.GENSYM, LispNames.MACROEXPAND, LispNames.MACROEXPAND_1, LispNames.VALUES,
			LispNames.WRITE_STRING, LispNames.WRITE_TO_STRING, LispNames.SYMBOL_NAME, LispNames.INTERN,
			LispNames.FIND_SYMBOL, LispNames.MAKE_SYMBOL, LispNames.BOUNDP, LispNames.FBOUNDP, LispNames.SYMBOL_VALUE,
			LispNames.FUNCTIONP, LispNames.VALUES_LIST, LispNames.NE);

	/** The {@code cl} variables. */
	private static final Set<String> CL_VARIABLES = Set.of(LispNames.PACKAGE_VAR, LispNames.READ_DEFAULT_FLOAT_FORMAT);

	/**
	 * The {@code cl} type-specifier (and clause-keyword) names that are not also
	 * function/macro names. Registered so they resolve bare inside user packages (a
	 * {@code (:use :cl)} package's {@code 'double-float} or {@code (integer 0)} must not
	 * become {@code pkg::double-float}); they are not callable and do not appear in the
	 * introspection listings.
	 */
	private static final Set<String> CL_TYPES = Set.of("integer", "number", "rational", "ratio", "real", "fixnum",
			"bignum", "single-float", "double-float", "short-float", "long-float", "unsigned-byte", "signed-byte",
			"boolean", "sequence", "array", "simple-array", "simple-vector", "simple-string", "base-string",
			"character", "base-char", "standard-char", "satisfies", "otherwise");

	/**
	 * Internal {@code %}-prefixed helpers owned by {@code cl} but excluded from the
	 * introspection listings.
	 */
	private static final Set<String> CL_INTERNALS = Set.of(LispNames.REMF_TAIL, LispNames.STRING_CONCAT,
			LispNames.BLOCK_INTERNAL, LispNames.ERROR_INTERNAL, LispNames.PUTHASH, LispNames.ASET,
			LispNames.ROW_MAJOR_ASET, LispNames.MAKE_STRING_OUTPUT_STREAM, LispNames.MAKE_STRING_INPUT_STREAM,
			LispNames.STRING_STREAM_CONTENTS, LispNames.ARRAYP_INTERNAL, LispNames.MV_SPILL);

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
	private static final Set<String> LINALG_FUNCTIONS = Set.of("zeros", "ones", "full", "eye", "arange", "linspace",
			"from-list", "to-list", "shape", "size", "reshape", "flatten", "transpose", "add", "sub", "mul", "div",
			"emap", "dot", "matmul", "outer", "sum", "mean", "amax", "amin", "argmax", "argmin", "norm", "trace", "det",
			"inv", "solve", "array-equal");

	private static final List<String> LINALG_FUNCTION_NAMES = sorted(LINALG_FUNCTIONS);

	private static final List<String> CL_FUNCTION_NAMES = sorted(CL_FUNCTIONS);

	private static final List<String> CL_MACRO_NAMES = sorted(CL_MACROS);

	private static final List<String> CL_SPECIAL_FORM_NAMES = sorted(CL_SPECIAL_FORMS);

	private static final Set<String> SPECIAL_OPERATOR_NAMES = union(CL_SPECIAL_FORMS, CL_MACROS);

	private final Map<String, LispPackage> packages = new HashMap<>();

	/**
	 * Package nicknames, mapping each nickname to the canonical package name. Seeded with
	 * the standard Common Lisp names ({@code common-lisp} for {@code cl},
	 * {@code common-lisp-user} for {@code cl-user}) so portable {@code (:use
	 * #:common-lisp)} clauses resolve; {@code defpackage :nicknames} adds more.
	 */
	private final Map<String, String> nicknames = new HashMap<>();

	/**
	 * Creates a registry seeded with the built-in packages.
	 */
	public PackageRegistry() {
		this.nicknames.put("common-lisp", LispNames.CL_PKG);
		this.nicknames.put("common-lisp-user", LispNames.CL_USER_PKG);
		define(new LispPackage(LispNames.CL_PKG, List.of(), CL_SYMBOLS, CL_EXTERNALS));
		// cl-user exports nothing, like the Common Lisp COMMON-LISP-USER package: its
		// symbols are reachable as cl-user::name, never cl-user:name.
		define(new LispPackage(LispNames.CL_USER_PKG, List.of(LispNames.CL_PKG), new HashSet<>(), Set.of()));
		define(new LispPackage(LispNames.RONTOLISP_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.VERSION, LispNames.LIST_FUNCTIONS, LispNames.LIST_MACROS,
						LispNames.LIST_SPECIAL_FORMS, LispNames.FETCH, LispNames.AWAIT, LispNames.PROMISEP,
						LispNames.THEN, LispNames.JSON_PARSE, LispNames.JSON_STRINGIFY, LispNames.URL_DECODE,
						LispNames.URL_ENCODE, LispNames.QUERY_PARAMS, LispNames.QUERY_PARAM, LispNames.URL_PATH,
						LispNames.URL_QUERY, LispNames.WASM_EXPORT, LispNames.WASM_IMPORT, LispNames.HTTP_HANDLER,
						LispNames.TCP_CONNECT, LispNames.TCP_LISTEN, LispNames.TCP_ACCEPT, LispNames.TCP_LOCAL_PORT,
						LispNames.TLS_CONNECT, LispNames.TLS_LISTEN, LispNames.TLS_LISTEN_PEM,
						LispNames.TLS_LISTEN_P12))));
		// numpy-style vector/matrix operations, implemented once in linalg.lisp and
		// spliced/loaded on demand (LinalgLibrary). Does not use cl; every function
		// is external.
		define(new LispPackage(LispNames.LINALG_PKG, List.of(), new HashSet<>(LINALG_FUNCTIONS)));
		// Interpreter-only Java interop. Does not use cl; its values (LispJavaObject)
		// run on the JVM interpreter only -- the compilers cannot lower them.
		define(new LispPackage(LispNames.JAVA_PKG, List.of(), new HashSet<>(Set.of(LispNames.JAVA_NEW,
				LispNames.JAVA_CALL, LispNames.JAVA_STATIC, LispNames.JAVA_FIELD, LispNames.JAVA_PROXY))));
		// A limited, API-compatible subset of ASDF (system definitions parsed from .asd
		// files as plain data -- see eval.AsdfSystems). Does not use cl; both symbols
		// are external.
		define(new LispPackage(LispNames.ASDF_PKG, List.of(),
				new HashSet<>(Set.of(LispNames.DEFSYSTEM, LispNames.LOAD_SYSTEM))));
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
	 * Splits a package-qualified symbol name into its package and member parts. A single
	 * colon ({@code pkg:name}) references an external symbol, a double colon
	 * ({@code pkg::name}) an internal one, mirroring Common Lisp. Returns {@code null}
	 * for unqualified names and for keywords (a leading {@code :}).
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
		String pkg = name.substring(0, idx);
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

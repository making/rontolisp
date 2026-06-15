package am.ik.rontolisp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A mutable registry of {@link LispPackage packages}, seeded with the three built-in
 * packages ({@code cl}, {@code cl-user}, {@code rontolisp}). The resolution rules in
 * {@link PackageResolver} operate generically over this registry, so a new package (or a
 * future {@code defpackage}) only needs to be registered here.
 */
public final class PackageRegistry {

	/**
	 * The {@code cl} special forms: operators handled natively by the evaluator and the
	 * compilers that have no function value ({@code #'if} is an error).
	 */
	private static final Set<String> CL_SPECIAL_FORMS = Set.of(LispNames.QUOTE, LispNames.IF, LispNames.LET,
			LispNames.PROGN, LispNames.SETQ, LispNames.LAMBDA, LispNames.WHILE, LispNames.FUNCTION, LispNames.DEFUN,
			LispNames.DEFVAR, LispNames.RETURN, LispNames.IN_PACKAGE);

	/**
	 * The {@code cl} macros: operators expanded by {@link LispMacroExpander} that have no
	 * function value. Names that expand internally but are also usable as function values
	 * ({@code first}, {@code length}, {@code 1+}, ...) are classified as functions.
	 */
	private static final Set<String> CL_MACROS = Set.of(LispNames.COND, LispNames.CASE, LispNames.AND, LispNames.OR,
			LispNames.WHEN, LispNames.UNLESS, LispNames.DOTIMES, LispNames.SETF, LispNames.PUSH, LispNames.POP,
			LispNames.REMF, LispNames.LET_STAR, LispNames.DOLIST, LispNames.INCF, LispNames.DECF, LispNames.FORMAT,
			LispNames.WITH_OPEN_FILE, LispNames.PROG1, LispNames.DO);

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
			LispNames.TANH, LispNames.GCD, LispNames.LCM, LispNames.SIGNUM, LispNames.EQ, LispNames.EQ_GENERAL,
			LispNames.EQL, LispNames.EQUAL, LispNames.LT, LispNames.GT, LispNames.LE, LispNames.GE, LispNames.CONS,
			LispNames.CAR, LispNames.CDR, LispNames.LIST, LispNames.APPEND, LispNames.NTHCDR, LispNames.RPLACA,
			LispNames.RPLACD, LispNames.MAPCAR, LispNames.MAPC, LispNames.MAPCAN, LispNames.APPLY, LispNames.SORT,
			LispNames.REDUCE, LispNames.EVERY, LispNames.SOME, LispNames.REMOVE, LispNames.REMOVE_IF,
			LispNames.REMOVE_IF_NOT, LispNames.NOT, LispNames.NULL, LispNames.ATOM, LispNames.NUMBERP,
			LispNames.INTEGERP, LispNames.FLOATP, LispNames.RATIONALP, LispNames.NUMERATOR, LispNames.DENOMINATOR,
			LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP, LispNames.FLOAT,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND, LispNames.ONE_PLUS,
			LispNames.ONE_MINUS, LispNames.ZEROP, LispNames.PLUSP, LispNames.MINUSP, LispNames.EVENP, LispNames.ODDP,
			LispNames.FIRST, LispNames.SECOND, LispNames.THIRD, LispNames.FOURTH, LispNames.NTH, LispNames.PRINT,
			LispNames.PRIN1, LispNames.PRINC, LispNames.TERPRI, LispNames.READ_LINE, LispNames.READ, LispNames.EVAL,
			LispNames.LOAD, LispNames.SYMBOL_FUNCTION, LispNames.LENGTH, LispNames.REVERSE, LispNames.MEMBER,
			LispNames.FIND, LispNames.FIND_IF, LispNames.POSITION, LispNames.COUNT, LispNames.ASSOC, LispNames.LAST,
			LispNames.REST, LispNames.PRINC_TO_STRING, LispNames.PRIN1_TO_STRING, LispNames.CONCATENATE,
			LispNames.STRING_UPCASE, LispNames.STRING_DOWNCASE, LispNames.STRING_CAPITALIZE, LispNames.SUBSEQ,
			LispNames.STRING_EQ, LispNames.STRING_EQUAL, LispNames.STRING_TRIM, LispNames.STRING_LEFT_TRIM,
			LispNames.STRING_RIGHT_TRIM, LispNames.OPEN, LispNames.CLOSE, LispNames.WRITE_LINE);

	/** The {@code cl} variables. */
	private static final Set<String> CL_VARIABLES = Set.of(LispNames.PACKAGE_VAR);

	/**
	 * Internal {@code %}-prefixed helpers owned by {@code cl} but excluded from the
	 * introspection listings.
	 */
	private static final Set<String> CL_INTERNALS = Set.of(LispNames.REMF_TAIL, LispNames.STRING_CONCAT,
			LispNames.BLOCK_INTERNAL);

	/**
	 * The names of the symbols owned by the {@code cl} package, derived as the union of
	 * the categorized sets above.
	 */
	private static final Set<String> CL_SYMBOLS = union(CL_SPECIAL_FORMS, CL_MACROS, CL_FUNCTIONS, CL_VARIABLES,
			CL_INTERNALS);

	private static final List<String> CL_FUNCTION_NAMES = sorted(CL_FUNCTIONS);

	private static final List<String> CL_MACRO_NAMES = sorted(CL_MACROS);

	private static final List<String> CL_SPECIAL_FORM_NAMES = sorted(CL_SPECIAL_FORMS);

	private static final Set<String> SPECIAL_OPERATOR_NAMES = union(CL_SPECIAL_FORMS, CL_MACROS);

	private final Map<String, LispPackage> packages = new HashMap<>();

	/**
	 * Creates a registry seeded with the three built-in packages.
	 */
	public PackageRegistry() {
		define(new LispPackage(LispNames.CL_PKG, List.of(), CL_SYMBOLS));
		define(new LispPackage(LispNames.CL_USER_PKG, List.of(LispNames.CL_PKG), new HashSet<>()));
		define(new LispPackage(LispNames.RONTOLISP_PKG, List.of(), new HashSet<>(Set.of(LispNames.VERSION,
				LispNames.LIST_FUNCTIONS, LispNames.LIST_MACROS, LispNames.LIST_SPECIAL_FORMS))));
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
	 * Returns the package with the given name.
	 * @param name the package name
	 * @return the package
	 * @throws LispPackageException if no such package is registered
	 */
	public LispPackage get(String name) {
		LispPackage pkg = this.packages.get(name);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + name);
		}
		return pkg;
	}

	/**
	 * Returns whether a package with the given name is registered.
	 * @param name the package name
	 * @return {@code true} if the package exists
	 */
	public boolean contains(String name) {
		return this.packages.containsKey(name);
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
	 * Composes a package-qualified symbol name, e.g.
	 * {@code qualify("rontolisp", "version")} yields {@code "rontolisp:version"}.
	 * @param pkg the package name
	 * @param member the member symbol name
	 * @return the qualified name
	 */
	public static String qualify(String pkg, String member) {
		return pkg + ":" + member;
	}

	/**
	 * Splits a package-qualified symbol name into its package and member parts. Returns
	 * {@code null} for unqualified names and for keywords (a leading {@code :}).
	 * @param name the symbol name
	 * @return the split parts, or {@code null} if the name is not package-qualified
	 */
	public static @Nullable QualifiedName splitQualified(String name) {
		int idx = name.indexOf(':');
		if (idx <= 0) {
			// No colon, or a leading colon (keyword): not package-qualified.
			return null;
		}
		return new QualifiedName(name.substring(0, idx), name.substring(idx + 1));
	}

	/**
	 * A package-qualified symbol name split into its package and member parts.
	 *
	 * @param pkg the package part
	 * @param member the member symbol part
	 */
	public record QualifiedName(String pkg, String member) {
	}

}

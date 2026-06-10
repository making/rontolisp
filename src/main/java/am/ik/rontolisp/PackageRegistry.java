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
	 * The names of the symbols owned by the {@code cl} package: the standard functions,
	 * macros, special forms and variables. Car/cdr compositions ({@code cadr},
	 * {@code cddr}, ...) are recognized separately by
	 * {@link LispMacroExpander#isCarCdrComposition}.
	 */
	private static final Set<String> CL_SYMBOLS = Set.of(LispNames.QUOTE, LispNames.IF, LispNames.LET, LispNames.PROGN,
			LispNames.SETQ, LispNames.LAMBDA, LispNames.FUNCALL, LispNames.WHILE, LispNames.ADD, LispNames.SUB,
			LispNames.MUL, LispNames.DIV, LispNames.MOD, LispNames.ABS, LispNames.MIN, LispNames.MAX, LispNames.SQRT,
			LispNames.ISQRT, LispNames.EXPT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS, LispNames.TAN,
			LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH, LispNames.TANH,
			LispNames.GCD, LispNames.LCM, LispNames.SIGNUM, LispNames.EQ, LispNames.EQ_GENERAL, LispNames.LT,
			LispNames.GT, LispNames.LE, LispNames.GE, LispNames.CONS, LispNames.CAR, LispNames.CDR, LispNames.LIST,
			LispNames.APPEND, LispNames.NTHCDR, LispNames.RPLACA, LispNames.RPLACD, LispNames.REMF_TAIL, LispNames.MAP,
			LispNames.REDUCE, LispNames.SETF, LispNames.PUSH, LispNames.POP, LispNames.REMF, LispNames.DEFUN,
			LispNames.COND, LispNames.AND, LispNames.OR, LispNames.NOT, LispNames.WHEN, LispNames.DOTIMES,
			LispNames.NULL, LispNames.ATOM, LispNames.NUMBERP, LispNames.INTEGERP, LispNames.FLOATP, LispNames.SYMBOLP,
			LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP, LispNames.FLOAT,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND, LispNames.ONE_PLUS,
			LispNames.ONE_MINUS, LispNames.ZEROP, LispNames.PLUSP, LispNames.MINUSP, LispNames.EVENP, LispNames.ODDP,
			LispNames.UNLESS, LispNames.FIRST, LispNames.SECOND, LispNames.THIRD, LispNames.FOURTH, LispNames.NTH,
			LispNames.PRINT, LispNames.PRIN1, LispNames.PRINC, LispNames.TERPRI, LispNames.READ_LINE, LispNames.READ,
			LispNames.EVAL, LispNames.LOAD, LispNames.IN_PACKAGE, LispNames.PACKAGE_VAR, LispNames.FUNCTION,
			LispNames.SYMBOL_FUNCTION, LispNames.LET_STAR, LispNames.DOLIST, LispNames.INCF, LispNames.DECF,
			LispNames.LENGTH, LispNames.REVERSE, LispNames.MEMBER, LispNames.ASSOC, LispNames.LAST);

	private final Map<String, LispPackage> packages = new HashMap<>();

	/**
	 * Creates a registry seeded with the three built-in packages.
	 */
	public PackageRegistry() {
		define(new LispPackage(LispNames.CL_PKG, List.of(), CL_SYMBOLS));
		define(new LispPackage(LispNames.CL_USER_PKG, List.of(LispNames.CL_PKG), new HashSet<>()));
		define(new LispPackage(LispNames.RONTOLISP_PKG, List.of(), new HashSet<>(Set.of(LispNames.VERSION))));
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

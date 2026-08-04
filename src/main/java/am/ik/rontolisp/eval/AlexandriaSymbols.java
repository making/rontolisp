package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * Rewrites ONE form of alexandria's {@code alexandria-1/symbols.lisp} -- the
 * {@code maybe-intern} helper behind {@code format-symbol} / {@code ensure-symbol} /
 * {@code symbolicate} -- so that "intern in the CURRENT package" means the package
 * current when the helper RUNS, not the one it was defined in.
 *
 * <p>
 * Upstream spells that as {@code (intern name (if (eq t package) *package* package))}.
 * rontolisp resolves {@code *package*} to the current package at RESOLUTION time
 * ({@code .kb/packages.md}: the canonical shape is a quoted package name), and inside a
 * defun body of alexandria's own file that constant is {@code ALEXANDRIA} forever -- so
 * every caller passing {@code t} got a symbol interned in ALEXANDRIA instead of its own
 * package. The failure is silent until something READS the symbol back by name:
 * fast-http's {@code multipart-parser.lisp} generates its 14 state constants with
 * {@code (format-symbol t "+~A+" state)} inside a {@code #.} and then references them by
 * their own package's spelling, so the whole system loaded and every parse died on "The
 * variable FAST-HTTP.MULTIPART-PARSER::+PARSING-DELIMITER-DASH-START+ is unbound".
 *
 * <p>
 * The rewrite routes the {@code t} branch through the ONE-argument {@code intern}, whose
 * contract already is "the current package" and which rontolisp answers from the live
 * resolver state rather than from a fold:
 *
 * <pre>
 * (intern name (if (eq t package) *package* package))
 *   -&gt; (if (eq t package) (intern name) (intern name package))
 * </pre>
 *
 * Everything else in the file stays VERBATIM. This is the narrow adaptation, not the
 * general fix: a dynamic {@code *package*} is a substrate change (see {@code .todo/255}),
 * and until it lands any OTHER library computing a symbol from a {@code *package*} it
 * captured across a function boundary has the same problem. The marker must occur exactly
 * once; an upstream release that changes the line throws rather than silently restoring
 * the broken fold.
 *
 * @see ShimLibraries#rewriteComponentSource
 */
public final class AlexandriaSymbols {

	/** The ASDF system name whose component sources this class rewrites. */
	public static final String SYSTEM = "alexandria";

	/** The one component file rewritten, relative to the system's base directory. */
	public static final String COMPONENT = "alexandria-1/symbols.lisp";

	private static final String MARKER = "(intern name (if (eq t package) *package* package))";

	private static final String REPLACEMENT = "(if (eq t package) (intern name) (intern name package))";

	private AlexandriaSymbols() {
	}

	/**
	 * Returns the rewritten source for alexandria's {@code symbols.lisp}, or null for any
	 * other component (the caller then uses the file verbatim).
	 * @param componentFile the component path relative to the system's base directory
	 * @param source the file's source text
	 * @return the rewritten source, or null when this class does not rewrite the file
	 */
	@Nullable public static String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		int at = source.indexOf(MARKER);
		if (at < 0 || source.indexOf(MARKER, at + 1) >= 0) {
			throw new IllegalStateException(
					"alexandria " + COMPONENT + ": expected exactly one occurrence of the maybe-intern marker " + MARKER
							+ " -- see eval.AlexandriaSymbols");
		}
		return source.substring(0, at) + REPLACEMENT + source.substring(at + MARKER.length());
	}

}

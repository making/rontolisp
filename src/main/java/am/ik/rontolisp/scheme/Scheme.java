package am.ik.rontolisp.scheme;

import java.util.List;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Scheme front end: a subset of R7RS-small {@code (scheme base)} and
 * {@code (scheme write)}, read case-sensitively and LOWERED to the Common Lisp core forms
 * every pipeline already consumes. Partial conformance by design and no compatibility
 * promise; the subset, the lowering table and the stated deviations are in
 * {@code .kb/scheme-frontend.md} and on the user-facing page
 * {@code doc/en/guides/scheme.md}.
 *
 * <p>
 * This package depends on the AST types and {@code reader} only. It is reached through
 * the source-language seam ({@code eval/SourceLanguage.SCHEME}), never directly, and its
 * run-time helpers are Common Lisp source shipped like every other library
 * ({@code eval/SchemeLibrary}).
 */
public final class Scheme {

	private Scheme() {
	}

	/**
	 * Reads a Scheme program and lowers it to Common Lisp core forms.
	 * @param source the program text
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file) {
		return SchemeLowering.ofFile(new SchemeReader(source, file)).lower();
	}

	/**
	 * Starts an interactive session -- a REPL, a playground -- that reads one buffer at a
	 * time.
	 * @return the session
	 */
	public static SchemeSession session() {
		return new SchemeSession();
	}

}

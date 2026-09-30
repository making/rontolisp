package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Clojure front end: a small subset of Clojure read case-sensitively and
 * LOWERED to the Common Lisp core forms every pipeline already consumes. A feasibility
 * spike, not a compatibility surface: the subset is whatever {@link #read} accepts, and
 * every deviation from Clojure is stated in the lowering's javadoc.
 *
 * <p>
 * This package depends on the core AST types and {@code reader} (for the exception type)
 * only. It is reached through the source-language seam
 * ({@code eval/SourceLanguage.CLOJURE}), never directly.
 */
public final class Clojure {

	private Clojure() {
	}

	/**
	 * Reads a Clojure program and lowers it to Common Lisp core forms.
	 * @param source the program text
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file) {
		ClojureReader reader = new ClojureReader(source, file);
		return ClojureLowering.lower(reader.readAll(), reader);
	}

	/**
	 * Starts an interactive session -- a REPL, a playground -- that reads one buffer at a
	 * time.
	 * @return the session
	 */
	public static ClojureSession session() {
		return new ClojureSession();
	}

}

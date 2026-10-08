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
		return read(source, file, null);
	}

	/**
	 * Reads a Clojure program and lowers it to Common Lisp core forms, expanding its
	 * macros through the macro evaluator.
	 * @param source the program text
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * @param macroEvaluator who evaluates one macro application in the macro-time
	 * environment, or {@code null} when macro call sites must fail
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file,
			@Nullable ClojureMacroEvaluator macroEvaluator) {
		return read(source, file, macroEvaluator, ClojureFiles.NONE);
	}

	/**
	 * Reads a Clojure program and lowers it to Common Lisp core forms, loading the
	 * project namespaces it requires from its source path through the files: each
	 * namespace's file lowers once, ahead of the form that required it.
	 * @param source the program text
	 * @param file the origin file for diagnostics and for the source path, or
	 * {@code null} when unknown (the working directory is the root)
	 * @param macroEvaluator who evaluates one macro application in the macro-time
	 * environment, or {@code null} when macro call sites must fail
	 * @param files where required namespace files are read from
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file,
			@Nullable ClojureMacroEvaluator macroEvaluator, ClojureFiles files) {
		return read(source, file, macroEvaluator, files, true);
	}

	/**
	 * {@link #read(String, String, ClojureMacroEvaluator, ClojureFiles)} for a target:
	 * where the host is (the interpreter, the JVM) a {@code java:} member can throw and
	 * take an exception, so a program naming one binds what a catch takes from the host
	 * and backs its exceptions with host ones; where it is not (wasm) the program lowers
	 * as if no host existed.
	 * @param source the program text
	 * @param file the origin file for diagnostics and for the source path, or
	 * {@code null} when unknown (the working directory is the root)
	 * @param macroEvaluator who evaluates one macro application in the macro-time
	 * environment, or {@code null} when macro call sites must fail
	 * @param files where required namespace files are read from
	 * @param hostTarget whether the target is one where the host is
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file,
			@Nullable ClojureMacroEvaluator macroEvaluator, ClojureFiles files, boolean hostTarget) {
		return read(source, file, macroEvaluator, files, hostTarget, ClojureBoundary.NONE);
	}

	/**
	 * {@link #read(String, String, ClojureMacroEvaluator, ClojureFiles, boolean)} with
	 * the host boundary {@code rontolisp.wasm} and {@code rontolisp.wit} lower against:
	 * the designators a declaration may name, the members a WIT interface binds and the
	 * exports a WIT world declares (the WIT files read through the files).
	 * @param source the program text
	 * @param file the origin file for diagnostics and for the source path, or
	 * {@code null} when unknown (the working directory is the root)
	 * @param macroEvaluator who evaluates one macro application in the macro-time
	 * environment, or {@code null} when macro call sites must fail
	 * @param files where required namespace files and WIT files are read from
	 * @param hostTarget whether the target is one where the host is
	 * @param boundary the host boundary
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file,
			@Nullable ClojureMacroEvaluator macroEvaluator, ClojureFiles files, boolean hostTarget,
			ClojureBoundary boundary) {
		ClojureReader reader = new ClojureReader(source, file);
		return ClojureHostClasses.lowering(files.javaClassLoader(),
				() -> ClojureLowering.lower(reader.readAll(), reader, macroEvaluator, files, hostTarget, boundary));
	}

	/**
	 * Starts an interactive session -- a REPL, a playground -- that reads one buffer at a
	 * time.
	 * @return the session
	 */
	public static ClojureSession session() {
		return new ClojureSession();
	}

	/**
	 * Starts an interactive session whose {@code require}s load project namespaces
	 * through the files, from the working directory's source path.
	 * @param files where required namespace files are read from
	 * @return the session
	 */
	public static ClojureSession session(ClojureFiles files) {
		return new ClojureSession(files);
	}

}

package am.ik.rontolisp.compiler;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Where a backend emits a compile-time WARNING -- a diagnostic that is printed rather
 * than thrown, so it never reaches the compile boundary's failure decoration
 * ({@code RontoLispCli.locateCompileFailure}).
 *
 * <p>
 * <b>Why this is not just {@code System.err.println}.</b> A backend may compile the same
 * program more than once and keep only the last result: {@code JvmLispCompiler} gates its
 * runtime helper GROUPS on a scan of the source program, checks the prediction against
 * the emitted bytecode, and re-runs the whole compile with the mispredicted group forced
 * on. Every warning of the discarded attempt had already been printed, so one
 * undefined-function call site warned TWICE for one compile ({@code .todo/151} phase 2
 * left this as a follow-up). A warning belongs to the attempt that SHIPS, which is only
 * known once the attempt finishes -- so an attempt buffers its warnings here and flushes
 * them when it is the one that produced the output.
 *
 * <p>
 * <b>Buffering is opt-in, per thread.</b> Without an open attempt {@link #warn} prints
 * straight through, which is what every other caller (the WASM backends, which never
 * re-run a compile) wants and keeps their output byte-identical. Within an attempt the
 * messages are deduplicated, so a call site reached twice by one attempt's own passes
 * says it once.
 */
public final class CompileWarnings {

	/** The warnings of the in-flight attempt, or {@code null} when not buffering. */
	private static final ThreadLocal<@Nullable Set<String>> PENDING = new ThreadLocal<>();

	private CompileWarnings() {
	}

	/**
	 * Emits a compile-time warning: buffered when an attempt is open on this thread (see
	 * {@link #startAttempt()}), printed to {@code System.err} otherwise.
	 * @param message the complete warning line, position prefix included
	 */
	public static void warn(String message) {
		Set<String> pending = PENDING.get();
		if (pending == null) {
			System.err.println(message);
		}
		else {
			pending.add(message);
		}
	}

	/**
	 * Starts buffering the warnings of one compile attempt on this thread, discarding
	 * anything a previous attempt left. Every path out of the attempt must reach
	 * {@link #flushAttempt()} or {@link #discardAttempt()}; a buffer left open would
	 * swallow the warnings of whatever compiles next on this thread. Not reentrant: only
	 * a backend's own top-level compile loop opens one.
	 */
	public static void startAttempt() {
		PENDING.set(new LinkedHashSet<>());
	}

	/**
	 * Prints the attempt's warnings, in the order they were first emitted, and ends it.
	 */
	public static void flushAttempt() {
		Set<String> pending = PENDING.get();
		PENDING.remove();
		if (pending != null) {
			pending.forEach(System.err::println);
		}
	}

	/**
	 * Ends the attempt without printing anything: its output was thrown away, so its
	 * warnings describe a compile that never happened.
	 */
	public static void discardAttempt() {
		PENDING.remove();
	}

}

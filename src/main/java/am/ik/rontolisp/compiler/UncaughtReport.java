package am.ik.rontolisp.compiler;

/**
 * What a condition nobody caught prints, once, on every backend.
 *
 * <p>
 * <b>The contract.</b> A signaled condition that escapes the top level writes exactly one
 * line to standard error -- {@code Unhandled condition: } followed by the condition's
 * report -- and the process then exits the way it always did (1 on the interpreter and
 * the JVM backend, a {@code wasm 'unreachable'} trap on the two wasm-GC backends). The
 * line is assembled here rather than at four call sites so a backend cannot drift: the
 * interpreter prints {@link #line} over the {@code LispEvalException} message, the JVM
 * backend emits the same concatenation into the generated {@code main}'s handler, and the
 * wasm-GC landing pad builds it from the thrown {@code $lisp-cond} payload.
 *
 * <p>
 * <b>Why not the JVM stack trace.</b> The trace names the interpreter's own frames, not
 * the program's: an uncaught error in a cl-postgres connect printed 212 lines of
 * {@code LispEvaluator.evalLet} above the one line that carried the diagnosis. It is
 * still one environment variable away -- {@link #DEBUG_ENV} -- because it is the right
 * answer when the bug being chased is rontolisp's own.
 */
public final class UncaughtReport {

	/** The one line's prefix; the condition's report follows it. */
	public static final String PREFIX = "Unhandled condition: ";

	/**
	 * The environment variable that additionally prints the JVM stack trace (interpreter
	 * and JVM backend; the wasm backends have no such trace to print). Being SET is what
	 * turns it on, whatever the value -- the generated {@code main}'s handler tests it
	 * with a single {@code ifnull} over {@code System.getenv}, and the two must agree.
	 */
	public static final String DEBUG_ENV = "RONTOLISP_DEBUG";

	private UncaughtReport() {
	}

	/**
	 * The complete line for a condition whose report is {@code message}.
	 * @param message the condition's report, or {@code null} when it has none
	 * @return the line to write to standard error, without its newline
	 */
	public static String line(String message) {
		return PREFIX + message;
	}

	/**
	 * Whether {@link #DEBUG_ENV} asks for the JVM stack trace as well.
	 * @return true when the variable is set
	 */
	public static boolean debugTraceRequested() {
		return System.getenv(DEBUG_ENV) != null;
	}

}

package am.ik.rontolisp.compiler;

import org.jspecify.annotations.Nullable;

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
 * <b>Where it happened: location lines UNDER the report.</b> The report line itself never
 * changes -- it is the condition's {@code princ} text, which {@code handler-case},
 * {@code format nil "~a"} and every pinned output read -- and the location follows it as
 * indented lines ({@link #atLine}, {@link #asyncLine}): the innermost form read from a
 * named file that the condition passed through and the named function enclosing it, then
 * one line per asynchronous boundary it crossed. A condition with nothing known (a
 * {@code -e} program, a form a macro built) prints no location line. The interpreter
 * prints them; the compiled backends print the report line alone.
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

	/** What every location line under the report starts with. */
	private static final String LOCATION_INDENT = "  ";

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
	 * The location line naming where the condition was signaled: the innermost form read
	 * from a named file that the condition passed through, and the innermost named
	 * function enclosing it.
	 * @param file the form's origin file
	 * @param line the form's 1-based line
	 * @param function the enclosing function's name, or {@code null} when the form is at
	 * top level or only anonymous functions enclose it
	 * @return the line, without its newline
	 */
	public static String atLine(String file, int line, @Nullable String function) {
		return LOCATION_INDENT + "at " + file + ":" + line + (function == null ? "" : " in " + function);
	}

	/**
	 * The location line for an asynchronous boundary the condition crossed: the async
	 * function whose body it escaped and the {@code await} that rethrew it.
	 * @param function the async function's name, or {@code null} for an async lambda
	 * @param file the await form's origin file, or {@code null} when unknown
	 * @param line the await form's 1-based line
	 * @return the line, without its newline
	 */
	public static String asyncLine(@Nullable String function, @Nullable String file, int line) {
		return LOCATION_INDENT + "in " + (function == null ? "an async lambda" : function + " (async)")
				+ (file == null ? "" : ", awaited at " + file + ":" + line);
	}

	/**
	 * Whether a line of standard error is one of the location lines under the report --
	 * what a harness comparing the report alone skips.
	 * @param line a line of standard error
	 * @return true for a location line
	 */
	public static boolean isLocationLine(String line) {
		return line.startsWith(LOCATION_INDENT + "at ") || line.startsWith(LOCATION_INDENT + "in ");
	}

	/**
	 * Whether {@link #DEBUG_ENV} asks for the JVM stack trace as well.
	 * @return true when the variable is set
	 */
	public static boolean debugTraceRequested() {
		return System.getenv(DEBUG_ENV) != null;
	}

}

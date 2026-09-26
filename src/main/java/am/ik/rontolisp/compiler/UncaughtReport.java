package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

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
 * named file that the condition passed through and the program function that form is
 * WRITTEN in (a lambda's forms are the function's around it), then one line per
 * asynchronous boundary it crossed. A condition with nothing known (a {@code -e} program,
 * a form a macro built) prints no location line. The interpreter and the JVM backend
 * print the same ones -- the JVM backend reads them off the stack trace
 * ({@code codegen.jvm.JvmUncaughtHandler}) -- and so does a wasm-GC module compiled with
 * {@code --report-locations}. The function is a property of the form, never of the frames
 * around it, so no backend's tail calls or inlining can change it; each names it as the
 * program spelled it ({@link #functionName}).
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

	/**
	 * What the line naming where the condition was signaled starts with; the file, a
	 * colon and the line follow ({@link #atLine}).
	 */
	public static final String AT_PREFIX = LOCATION_INDENT + "at ";

	/** What joins the function holding the form to {@link #AT_PREFIX}'s position. */
	public static final String IN_FUNCTION = " in ";

	/**
	 * What an async boundary's line starts with; {@link #asyncHead} and, when known,
	 * {@link #AWAITED_AT} and the await's position follow ({@link #asyncLine}).
	 */
	public static final String ASYNC_PREFIX = LOCATION_INDENT + "in ";

	/** What joins an async boundary's head to the position of the await that rethrew. */
	public static final String AWAITED_AT = ", awaited at ";

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
		return AT_PREFIX + file + ":" + line + (function == null ? "" : IN_FUNCTION + function);
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
		return ASYNC_PREFIX + asyncHead(function) + (file == null ? "" : AWAITED_AT + file + ":" + line);
	}

	/**
	 * The name a location line calls a function by, given the name it was defined under:
	 * the program's own spelling where a lowering renamed it -- a method body is its
	 * generic function ({@code %AREA--m0} is {@code AREA}), a top-level defun a nested
	 * one redefines keeps its name ({@link NestedDefunRedefinition}). One mapping for
	 * every backend, so their lines stay identical.
	 * @param defined the name the function was defined under
	 * @return the name to report
	 */
	public static String functionName(String defined) {
		String generic = LispMacroExpander.genericOfMethodFunction(defined);
		return generic != null ? generic : NestedDefunRedefinition.originalName(defined);
	}

	/**
	 * A non-top-level {@code defun}'s lowering, {@code (setq name (lambda ...))}: the
	 * lambda it installs and the name it installs it under -- a function the report
	 * names, as the interpreter installs a nested defun as a named function.
	 *
	 * @param lambda the lambda form
	 * @param name the name it is defined under (a {@code setf} function's included)
	 */
	public record NestedDefun(LispCons lambda, String name) {
	}

	/**
	 * The lambda and name of a nested {@code defun}'s lowering; see {@link NestedDefun}.
	 * @param lowered what {@code LispMacroExpander.expandDefun} answered for it
	 * @return the pair, or {@code null} for any other shape
	 */
	public static @Nullable NestedDefun nestedDefun(LispVal lowered) {
		if (!(lowered instanceof LispCons setq && setq.cdr() instanceof LispCons nameCell
				&& nameCell.cdr() instanceof LispCons lambdaCell && lambdaCell.car() instanceof LispCons lambda)) {
			return null;
		}
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(nameCell.car());
		if (setfPlace != null) {
			return new NestedDefun(lambda, LispMacroExpander.setfFunctionName(setfPlace.name()));
		}
		return nameCell.car() instanceof LispSymbol symbol ? new NestedDefun(lambda, symbol.name()) : null;
	}

	/**
	 * What an async boundary's line names the body it escaped by -- the part a compiled
	 * program knows before it runs, since the async function is fixed where
	 * {@code %async-run} is called.
	 * @param function the async function's name, or {@code null} for an async lambda
	 * @return the head, without {@link #ASYNC_PREFIX}
	 */
	public static String asyncHead(@Nullable String function) {
		return function == null ? "an async lambda" : function + " (async)";
	}

	/**
	 * Whether a line of standard error is one of the location lines under the report --
	 * what a harness comparing the report alone skips.
	 * @param line a line of standard error
	 * @return true for a location line
	 */
	public static boolean isLocationLine(String line) {
		return line.startsWith(AT_PREFIX) || line.startsWith(ASYNC_PREFIX);
	}

	/**
	 * Whether {@link #DEBUG_ENV} asks for the JVM stack trace as well.
	 * @return true when the variable is set
	 */
	public static boolean debugTraceRequested() {
		return System.getenv(DEBUG_ENV) != null;
	}

}

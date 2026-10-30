package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * What a thread runs where an error no handler handles is signalled, before anything
 * unwinds, and where an interrupt stops it: SWANK's debugger
 * ({@link CommonLispSwank#serve}). Installed per thread
 * ({@link LispEvaluator#withDebugger}); a thread without one unwinds as before.
 */
interface Debugger {

	/**
	 * Runs on the thread an error was signalled on, its handlers declined and no
	 * {@code handler-case} taking it: every restart the program established is still
	 * active. Leaves by a restart's transfer of control; returning declines, and the
	 * error unwinds as if no debugger were there.
	 * @param condition the condition
	 * @param message its report
	 * @param env the scope the error was signalled in, or null where none is known
	 * @param operator a frame for the built-in that signalled it, on top of the thread's
	 * stack, or null
	 */
	void debug(LispVal condition, String message, @Nullable Environment env, @Nullable CallFrame operator);

	/**
	 * Runs on a thread an interrupt stopped ({@link LispEvaluator#interrupt}).
	 * @return true to go on with the evaluation, false to stop it as without a debugger
	 */
	boolean interrupted();

}

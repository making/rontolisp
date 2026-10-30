package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * One frame of a thread's call stack, kept while the evaluator tracks frames for a
 * debugger ({@link LispEvaluator#enableDebugging}): a function the interpreter entered,
 * or a marker a caller pushed for what it evaluates ({@code (EVAL form)}, a SWANK
 * request).
 *
 * <p>
 * A tail call replaces the function of the frame it runs in ({@link #replaced}), as the
 * evaluator's loop replaces the form: the frames a debugger lists are the activations
 * still waiting for a value, not every call made.
 */
final class CallFrame {

	/** The frame below this one, or null at the bottom of the thread's stack. */
	final @Nullable CallFrame parent;

	/** The thread's stack top: one cell, so a pop needs no thread-local lookup. */
	private final @Nullable CallFrame @Nullable [] top;

	/** A marker's call, as the debugger prints it; null for a function's frame. */
	final @Nullable LispVal call;

	/**
	 * The scope the call form was evaluated in: the caller's innermost scope when the
	 * caller is interpreted code, so the caller's frame can show its locals.
	 */
	final @Nullable Environment caller;

	private @Nullable LispLambda lambda;

	private List<LispVal> args;

	private @Nullable Environment entry;

	private CallFrame(@Nullable CallFrame parent, @Nullable CallFrame @Nullable [] top, @Nullable LispVal call,
			@Nullable LispLambda lambda, List<LispVal> args, @Nullable Environment entry,
			@Nullable Environment caller) {
		this.parent = parent;
		this.top = top;
		this.call = call;
		this.lambda = lambda;
		this.args = args;
		this.entry = entry;
		this.caller = caller;
	}

	/**
	 * Pushes a function's frame on a thread's stack.
	 * @param top the thread's stack top
	 * @param lambda the function
	 * @param args its arguments
	 * @param entry its call scope
	 * @param caller the scope the call was evaluated in
	 * @return the frame, to {@link #exit} when the call returns
	 */
	static CallFrame enter(@Nullable CallFrame[] top, LispLambda lambda, List<LispVal> args, Environment entry,
			@Nullable Environment caller) {
		CallFrame frame = new CallFrame(top[0], top, null, lambda, args, entry, caller);
		top[0] = frame;
		return frame;
	}

	/**
	 * Pushes a marker on a thread's stack.
	 * @param top the thread's stack top
	 * @param call the call the marker shows
	 * @return the frame, to {@link #exit} when what it marks returns
	 */
	static CallFrame mark(@Nullable CallFrame[] top, LispVal call) {
		CallFrame frame = new CallFrame(top[0], top, call, null, List.of(), null, null);
		top[0] = frame;
		return frame;
	}

	/**
	 * A frame for a built-in a condition was signalled in, on top of a stack without
	 * being pushed on it.
	 * @param parent the stack's top
	 * @param call the built-in's call
	 * @return the frame
	 */
	static CallFrame operator(@Nullable CallFrame parent, LispVal call) {
		return new CallFrame(parent, null, call, null, List.of(), null, null);
	}

	/**
	 * The function a tail call runs in this frame instead of the one it entered.
	 * @param lambda the function
	 * @param args its arguments
	 * @param entry its call scope
	 * @return this frame
	 */
	CallFrame replaced(LispLambda lambda, List<LispVal> args, Environment entry) {
		this.lambda = lambda;
		this.args = args;
		this.entry = entry;
		return this;
	}

	/** Pops this frame, the top of its thread's stack. */
	void exit() {
		@Nullable CallFrame[] cell = this.top;
		if (cell != null) {
			cell[0] = this.parent;
		}
	}

	/**
	 * The function this frame runs, or null for a marker.
	 * @return the function
	 */
	@Nullable LispLambda lambda() {
		return this.lambda;
	}

	/**
	 * The function's arguments.
	 * @return the arguments
	 */
	List<LispVal> args() {
		return this.args;
	}

	/**
	 * The function's call scope, or null for a marker.
	 * @return the scope
	 */
	@Nullable Environment entry() {
		return this.entry;
	}

}

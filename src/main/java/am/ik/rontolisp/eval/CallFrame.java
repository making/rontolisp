package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;

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

	/** How many forms a frame remembers stepping onto, a power of two. */
	private static final int RECENT = 8;

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

	/**
	 * The last source forms -- read from a file or an editor's text ({@link LocatedCons})
	 * -- this frame stepped onto, a ring: the newest is the one it waits in while a frame
	 * further in runs, or where a condition was signalled.
	 */
	private @Nullable LispVal @Nullable [] recent;

	/** How many forms {@link #at} noted. */
	private int steps;

	/** The tags of the {@code catch} forms active in this frame, innermost last. */
	private @Nullable List<LispVal> catches;

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
	 * Notes the source form this frame evaluates now.
	 * @param form the form
	 */
	void at(LocatedCons form) {
		@Nullable LispVal[] ring = this.recent;
		if (ring == null) {
			ring = new LispVal[RECENT];
			this.recent = ring;
		}
		ring[this.steps++ & (RECENT - 1)] = form;
	}

	/**
	 * The source forms this frame stepped onto last, newest first.
	 * @return the forms
	 */
	List<LispVal> recentForms() {
		@Nullable LispVal[] ring = this.recent;
		if (ring == null) {
			return List.of();
		}
		List<LispVal> forms = new ArrayList<>(RECENT);
		for (int i = 1; i <= Math.min(this.steps, RECENT); i++) {
			LispVal form = ring[(this.steps - i) & (RECENT - 1)];
			if (form != null) {
				forms.add(form);
			}
		}
		return forms;
	}

	/**
	 * Notes a {@code catch} established in this frame.
	 * @param tag its tag
	 */
	void pushCatch(LispVal tag) {
		List<LispVal> tags = this.catches;
		if (tags == null) {
			tags = new ArrayList<>(2);
			this.catches = tags;
		}
		tags.add(tag);
	}

	/** Drops the innermost {@code catch} {@link #pushCatch} noted. */
	void popCatch() {
		List<LispVal> tags = this.catches;
		if (tags != null && !tags.isEmpty()) {
			tags.removeLast();
		}
	}

	/**
	 * The tags of the {@code catch} forms active in this frame, innermost first.
	 * @return the tags
	 */
	List<LispVal> catchTags() {
		List<LispVal> tags = this.catches;
		return tags == null ? List.of() : List.copyOf(tags.reversed());
	}

	/**
	 * Whether this frame is a function's activation on its thread's stack, which a
	 * debugger can restart or return from ({@link FrameSignal}).
	 * @return whether it is
	 */
	boolean restartable() {
		return this.lambda != null && this.top != null;
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

package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

/**
 * Internal control-flow signal used by the interpreter to implement every {@code block}
 * exit -- {@code (return-from name value)}, {@code (return value)} and the implicit exits
 * the iteration macros lower to. It carries the TARGET BLOCK'S IDENTITY (the scope the
 * establishing {@code block} created, resolved LEXICALLY at the exit site), so only that
 * one activation catches it: a handler built inside a block exits that block even when
 * the signal is raised deep inside another function's loop, and a recursive function's
 * inner activation cannot swallow an outer one's exit. The name rides along for the error
 * message an exit whose block is no longer active produces. Like {@link ThrowSignal} it
 * is not a user-visible error and suppresses its stack trace.
 */
final class BlockReturnSignal extends RuntimeException {

	private final transient Object target;

	private final transient String name;

	private final transient LispVal value;

	BlockReturnSignal(Object target, String name, LispVal value) {
		super(null, null, false, false);
		this.target = target;
		this.name = name;
		this.value = value;
	}

	/** The identity of the block activation this exit targets. */
	Object target() {
		return this.target;
	}

	String name() {
		return this.name;
	}

	LispVal value() {
		return this.value;
	}

}

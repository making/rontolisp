package am.ik.rontolisp.eval;

import java.util.function.Supplier;

import am.ik.rontolisp.LispVal;

/**
 * A debugger's transfer of control to a frame of its thread's stack, SBCL's
 * {@code unwind-to-frame-and-call}: every frame further in unwinds, then the frame's
 * activation ends with what the action answers -- the values a {@code return-from-frame}
 * evaluated, or the function called again with its arguments for a {@code restart-frame}.
 * Only the evaluator's activation that pushed the frame takes it. Not a user-visible
 * error; it suppresses its stack trace.
 */
final class FrameSignal extends RuntimeException {

	private final transient CallFrame frame;

	private final transient Supplier<LispVal> action;

	FrameSignal(CallFrame frame, Supplier<LispVal> action) {
		super(null, null, false, false);
		this.frame = frame;
		this.action = action;
	}

	/** The frame this transfer ends. */
	CallFrame frame() {
		return this.frame;
	}

	/** What answers in the frame's place, run once the frame is off the stack. */
	Supplier<LispVal> action() {
		return this.action;
	}

}
